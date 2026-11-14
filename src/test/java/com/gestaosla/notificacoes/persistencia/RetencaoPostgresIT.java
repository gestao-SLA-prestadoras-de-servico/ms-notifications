package com.gestaosla.notificacoes.persistencia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.gestaosla.notificacoes.eventos.CanalNotificacao;
import com.gestaosla.notificacoes.eventos.TipoEvento;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"notificacoes.retencao.cron=-", "notificacoes.consumidor-email.enabled=false",
        "notificacoes.consumidor-interno.enabled=false", "spring.datasource.url=${IT_DB_URL}",
        "spring.datasource.username=${IT_DB_USERNAME}", "spring.datasource.password=${IT_DB_PASSWORD}",
        "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"})
class RetencaoPostgresIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant AGORA = Instant.parse("2026-11-12T12:00:00Z");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry r) {
        r.add("spring.flyway.default-schema", () -> SCHEMA);
        r.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired EventoProcessadoRepository processados;

    @BeforeEach void limpar() { notificacoes.deleteAll(); processados.deleteAll(); }

    @Test void excluiEmLotesSomenteAlemDe90DiasEPreservaMarcadores() {
        Instant limite = AGORA.minus(Duration.ofDays(90));
        for (int i = 0; i < 5; i++) criar("antigo-" + i, limite.minusSeconds(i + 1));
        criar("limite", limite);
        criar("recente", limite.plusSeconds(1));
        var marcador = new EventoProcessado("antigo-0", CanalNotificacao.INTERNA, AGORA);
        processados.saveAndFlush(marcador);
        var job = new RetencaoNotificacoes(jdbc, Clock.fixed(AGORA, ZoneOffset.UTC), manager, 2);
        job.executar();
        assertThat(notificacoes.count()).isEqualTo(2);
        assertThat(notificacoes.findByEventoIdAndDestinatarioSub("limite", "sub")).isPresent();
        assertThat(notificacoes.findByEventoIdAndDestinatarioSub("recente", "sub")).isPresent();
        assertThat(processados.existsByEventoIdAndCanal("antigo-0", CanalNotificacao.INTERNA)).isTrue();
        job.executar();
        assertThat(notificacoes.count()).isEqualTo(2);
    }

    @Test void recusaTamanhoDeLoteInvalido() {
        assertThatThrownBy(() -> new RetencaoNotificacoes(jdbc, Clock.systemUTC(), manager, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetencaoNotificacoes(jdbc, Clock.systemUTC(), manager, 10001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ignoraLinhaBloqueadaEExpurgaNaProximaExecucao() throws Exception {
        Instant antigo = AGORA.minus(Duration.ofDays(91));
        criar("bloqueado", antigo);
        criar("livre", antigo);
        var id = notificacoes.findByEventoIdAndDestinatarioSub("bloqueado", "sub").orElseThrow().getId();
        var bloqueou = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var operacao = worker.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("select id from notificacao_interna where id=? for update", UUID.class, id);
                bloqueou.countDown();
                try { if (!liberar.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertThat(bloqueou.await(5, TimeUnit.SECONDS)).isTrue();
                var job = new RetencaoNotificacoes(jdbc, Clock.fixed(AGORA, ZoneOffset.UTC), manager, 2);
                job.executar();
                assertThat(notificacoes.findByEventoIdAndDestinatarioSub("livre", "sub")).isEmpty();
                assertThat(notificacoes.findByEventoIdAndDestinatarioSub("bloqueado", "sub")).isPresent();
                liberar.countDown(); operacao.get(5, TimeUnit.SECONDS);
                job.executar();
                assertThat(notificacoes.count()).isZero();
            } finally { liberar.countDown(); }
        }
    }

    private void criar(String evento, Instant criado) {
        notificacoes.saveAndFlush(new NotificacaoInterna("sub", evento, TipoEvento.ATRIBUICAO,
                "chamado", "#1", "Titulo", criado));
    }
}
