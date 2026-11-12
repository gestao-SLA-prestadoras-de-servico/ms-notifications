package com.gestaosla.notificacoes.eventos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.gestaosla.notificacoes.persistencia.*;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "notificacoes.consumidor-email.enabled=false",
        "notificacoes.consumidor-interno.enabled=false",
        "spring.datasource.url=${IT_DB_URL}", "spring.datasource.username=${IT_DB_USERNAME}",
        "spring.datasource.password=${IT_DB_PASSWORD}", "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"
})
@Timeout(25)
class IdempotenciaPostgresIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T13:00:00Z"), ZoneOffset.UTC);

    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventoProcessadoRepository processados;
    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired LeitorEventoNotificacao leitor;

    @BeforeEach
    void limpar() {
        notificacoes.deleteAll();
        processados.deleteAll();
    }

    private ProcessadorEventoIdempotente processor() {
        return new ProcessadorEventoIdempotente(manager, jdbc, processados, CLOCK, "10s");
    }

    private void gravar(String event, String sub) {
        notificacoes.saveAndFlush(new NotificacaoInterna(sub, event, TipoEvento.ATRIBUICAO,
                "1042", "#1042", "Manutenção", CLOCK.instant()));
    }

    @Test
    void sucessoSoApareceDepoisDaAcaoEReentregaNaoRepeteEfeitos() {
        var processor = processor();
        assertThat(processor.processar("evento", CanalNotificacao.INTERNA, () -> {
            assertThat(processados.existsByEventoIdAndCanal("evento", CanalNotificacao.INTERNA)).isFalse();
            gravar("evento", "sub-1");
            gravar("evento", "sub-2");
        })).isEqualTo(ResultadoProcessamento.PROCESSADO);
        assertThat(processor.processar("evento", CanalNotificacao.INTERNA, () -> fail("Não repetir")))
                .isEqualTo(ResultadoProcessamento.JA_PROCESSADO);
        assertThat(notificacoes.count()).isEqualTo(2);
        assertThat(processados.findAll()).singleElement().satisfies(e -> assertThat(e.getProcessadoEm()).isEqualTo(CLOCK.instant()));
    }

    @Test
    void seisInstanciasConcorrentesExecutamUmaVezComLockNoBanco() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<ResultadoProcessamento>>();
            try {
                results.add(executor.submit(() -> processor().processar("concorrente", CanalNotificacao.INTERNA, () -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    esperar(release);
                    gravar("concorrente", "sub-1");
                    gravar("concorrente", "sub-2");
                })));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 5; i++) {
                    results.add(executor.submit(() -> processor().processar("concorrente", CanalNotificacao.INTERNA, () -> calls.incrementAndGet())));
                }
                aguardarContencao();
            } finally {
                release.countDown();
            }
            var outcomes = new ArrayList<ResultadoProcessamento>();
            for (var future : results) outcomes.add(future.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).containsOnlyOnce(ResultadoProcessamento.PROCESSADO);
            assertThat(outcomes.stream().filter(r -> r == ResultadoProcessamento.JA_PROCESSADO).count()).isEqualTo(5);
            assertThat(calls).hasValue(1);
            assertThat(notificacoes.count()).isEqualTo(2);
            assertThat(processados.count()).isEqualTo(1);
        }
    }

    @Test
    void falhaParcialReverteTodosOsDestinatariosELiberaLockParaNovaTentativa() {
        assertThatThrownBy(() -> processor().processar("falha", CanalNotificacao.INTERNA, () -> {
            gravar("falha", "sub-1");
            throw new IllegalStateException("Falha antes do segundo destinatário");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isZero();
        processor().processar("falha", CanalNotificacao.INTERNA, () -> {
            gravar("falha", "sub-1");
            gravar("falha", "sub-2");
        });
        assertThat(notificacoes.count()).isEqualTo(2);
    }

    @Test
    void falhaRealNoCommitNaoConfirmaRabbitNemDeixaNotificacaoOuMarcador() throws Exception {
        jdbc.execute("create function rejeitar_commit() returns trigger language plpgsql as $$ begin raise exception 'Falha simulada no commit'; end; $$");
        jdbc.execute("create constraint trigger falhar_sucesso after insert on evento_processado deferrable initially deferred for each row execute function rejeitar_commit()");
        var channel = mock(Channel.class);
        var invoked = new AtomicInteger();
        try {
            String payload = java.nio.file.Files.readString(java.nio.file.Path.of("docs/contratos/exemplos/validos/atribuicao.json"));
            var mensagem = new org.springframework.amqp.core.Message(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), new org.springframework.amqp.core.MessageProperties());
            var adapter = new ProcessadorMensagemNotificacao(leitor, processor(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            assertThatThrownBy(() -> adapter.processar(mensagem, channel, CanalNotificacao.INTERNA, e -> {
                invoked.incrementAndGet();
                gravar(e.eventoId(), "sub-1");
            })).isInstanceOf(RuntimeException.class);
            assertThat(invoked).hasValue(1);
            verifyNoInteractions(channel);
            assertThat(notificacoes.count()).isZero();
            assertThat(processados.count()).isZero();
        } finally {
            jdbc.execute("drop trigger falhar_sucesso on evento_processado");
            jdbc.execute("drop function rejeitar_commit()");
        }
    }

    @Test
    void mesmoEventoEmCanaisDistintosPodeExecutarEmParalelo() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var email = executor.submit(() -> processor().processar("evento", CanalNotificacao.EMAIL, () -> {
                entered.countDown(); esperar(release);
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var interna = executor.submit(() -> processor().processar("evento", CanalNotificacao.INTERNA, () -> gravar("evento", "sub-1")));
                assertThat(interna.get(5, TimeUnit.SECONDS)).isEqualTo(ResultadoProcessamento.PROCESSADO);
            } finally { release.countDown(); }
            assertThat(email.get(5, TimeUnit.SECONDS)).isEqualTo(ResultadoProcessamento.PROCESSADO);
            assertThat(processados.count()).isEqualTo(2);
        }
    }

    @Test
    void eventosDistintosNaoUsamLockGlobal() throws Exception {
        var both = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> processor().processar("a", CanalNotificacao.INTERNA, () -> { both.countDown(); esperar(both); }));
            var b = executor.submit(() -> processor().processar("b", CanalNotificacao.INTERNA, () -> { both.countDown(); esperar(both); }));
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(ResultadoProcessamento.PROCESSADO);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(ResultadoProcessamento.PROCESSADO);
        }
    }

    @Test
    void exclusaoDaNotificacaoNaoPermiteReprocessarEvento() {
        processor().processar("evento", CanalNotificacao.INTERNA, () -> gravar("evento", "sub-1"));
        notificacoes.deleteAll();
        assertThat(processor().processar("evento", CanalNotificacao.INTERNA, () -> fail("Não recriar")))
                .isEqualTo(ResultadoProcessamento.JA_PROCESSADO);
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isEqualTo(1);
    }

    @Test
    void retornaSomenteAposCommitMesmoComTransacaoExterna() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            processor().processar("evento", CanalNotificacao.INTERNA, () -> gravar("evento", "sub-1"));
            throw new IllegalStateException("Rollback da transação externa");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(processados.existsByEventoIdAndCanal("evento", CanalNotificacao.INTERNA)).isTrue();
        assertThat(notificacoes.count()).isEqualTo(1);
    }

    @Test
    void timeoutDeLockNaoExecutaAcaoNemGravaSucesso() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> processor().processar("bloqueado", CanalNotificacao.INTERNA, () -> {
                entered.countDown(); esperar(release);
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = new ProcessadorEventoIdempotente(manager, jdbc, processados, CLOCK, "100ms");
                assertThatThrownBy(() -> second.processar("bloqueado", CanalNotificacao.INTERNA, () -> fail("Não executar")))
                        .isInstanceOf(DataAccessException.class);
                assertThat(processados.count()).isZero();
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ResultadoProcessamento.PROCESSADO);
            assertThat(processor().processar("bloqueado", CanalNotificacao.INTERNA, () -> fail("Não repetir")))
                    .isEqualTo(ResultadoProcessamento.JA_PROCESSADO);
        }
    }

    private void aguardarContencao() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and not granted", Long.class)).isPositive());
    }

    private static void esperar(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout no teste");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(ex);
        }
    }
}
