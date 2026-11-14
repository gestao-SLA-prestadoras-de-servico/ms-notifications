package com.gestaosla.notificacoes.eventos;

import java.time.Clock;
import java.util.Objects;

import com.gestaosla.notificacoes.persistencia.EventoProcessado;
import com.gestaosla.notificacoes.persistencia.EventoProcessadoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProcessadorEventoIdempotente {
    private final JdbcTemplate jdbc;
    private final EventoProcessadoRepository processados;
    private final Clock clock;
    private final String lockTimeout;
    private final TransactionTemplate transaction;

    public ProcessadorEventoIdempotente(PlatformTransactionManager manager, JdbcTemplate jdbc,
            EventoProcessadoRepository processados, Clock clock,
            @Value("${notificacoes.processamento.lock-timeout:10s}") String lockTimeout) {
        this.jdbc = jdbc;
        this.processados = processados;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
        this.transaction = new TransactionTemplate(manager);
        // O retorno deve significar commit concluído, mesmo se o chamador tiver uma transação.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Após esperar o lock, a consulta precisa enxergar o commit da instância anterior.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** A ação é síncrona; suas escritas devem participar desta transação (propagação REQUIRED). */
    public ResultadoProcessamento processar(String eventoId, CanalNotificacao canal, Runnable acao) {
        if (eventoId == null || eventoId.isBlank()) {
            throw new IllegalArgumentException("eventoId obrigatório");
        }
        Objects.requireNonNull(canal, "canal obrigatório");
        Objects.requireNonNull(acao, "ação obrigatória");
        return transaction.execute(status -> {
            jdbc.query("select set_config('lock_timeout', ?, true)", rs -> { }, lockTimeout);
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { },
                    "ms-notificacoes:" + canal.name() + ":" + eventoId);
            if (processados.existsByEventoIdAndCanal(eventoId, canal)) {
                return ResultadoProcessamento.JA_PROCESSADO;
            }
            acao.run();
            processados.saveAndFlush(new EventoProcessado(eventoId, canal, clock.instant()));
            return ResultadoProcessamento.PROCESSADO;
        });
    }
}
