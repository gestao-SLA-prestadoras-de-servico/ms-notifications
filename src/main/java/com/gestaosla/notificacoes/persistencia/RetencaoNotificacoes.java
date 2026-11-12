package com.gestaosla.notificacoes.persistencia;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RetencaoNotificacoes {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetencaoNotificacoes.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int tamanhoLote;
    private final TransactionTemplate transacao;

    public RetencaoNotificacoes(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager,
            @Value("${notificacoes.retencao.tamanho-lote:500}") int tamanhoLote) {
        if (tamanhoLote < 1 || tamanhoLote > 10000) throw new IllegalArgumentException("Tamanho de lote inválido");
        this.jdbc = jdbc;
        this.clock = clock;
        this.tamanhoLote = tamanhoLote;
        this.transacao = new TransactionTemplate(manager);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(cron = "${notificacoes.retencao.cron:0 0 3 * * *}", zone = "${notificacoes.retencao.zone:UTC}", scheduler = "retencaoScheduler")
    public void executar() {
        Instant limite = clock.instant().minus(Duration.ofDays(90));
        long total = 0;
        int removidas;
        do {
            Integer resultado = transacao.execute(status -> jdbc.update("""
                    DELETE FROM notificacao_interna WHERE id IN (
                        SELECT id FROM notificacao_interna WHERE criado_em < ?
                        ORDER BY criado_em, id LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    """, Timestamp.from(limite), tamanhoLote));
            removidas = resultado == null ? 0 : resultado;
            total += removidas;
        } while (removidas == tamanhoLote);
        LOGGER.info("Retenção de notificações concluída: removidas={}", total);
    }
}
