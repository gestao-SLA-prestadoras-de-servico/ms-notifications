package com.gestaosla.notificacoes.persistencia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import com.gestaosla.notificacoes.eventos.TipoEvento;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"notificacoes.retencao.cron=*/1 * * * * *", "notificacoes.consumidor-email.enabled=false",
        "notificacoes.consumidor-interno.enabled=false", "spring.datasource.url=${IT_DB_URL}",
        "spring.datasource.username=${IT_DB_USERNAME}", "spring.datasource.password=${IT_DB_PASSWORD}",
        "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"})
@Import(AgendamentoRetencaoPostgresIT.Relogio.class)
class AgendamentoRetencaoPostgresIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant AGORA = Instant.parse("2026-11-12T12:00:00Z");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry r) {
        r.add("spring.flyway.default-schema", () -> SCHEMA);
        r.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }
    @TestConfiguration static class Relogio {
        @Bean @Primary Clock relogioFixo() { return Clock.fixed(AGORA, ZoneOffset.UTC); }
    }
    @Autowired NotificacaoInternaRepository notificacoes;
    @Test void cronConfiguradoExecutaAutomaticamente() {
        notificacoes.saveAndFlush(new NotificacaoInterna("sub", "evento", TipoEvento.ATRIBUICAO,
                "chamado", "#1", "Titulo", AGORA.minus(Duration.ofDays(91))));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(notificacoes.count()).isZero());
    }
}
