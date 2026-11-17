package com.gestaosla.notificacoes.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import com.gestaosla.notificacoes.persistencia.NotificacaoInternaRepository;
import com.gestaosla.notificacoes.suporte.TokensKeycloak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fluxo completo: entrega RabbitMQ, persistência PostgreSQL e API com JWT real. */
@SpringBootTest(properties = {
        "spring.datasource.url=${IT_DB_URL}", "spring.datasource.username=${IT_DB_USERNAME}",
        "spring.datasource.password=${IT_DB_PASSWORD}", "spring.rabbitmq.host=${IT_RABBITMQ_HOST}",
        "spring.rabbitmq.port=${IT_RABBITMQ_PORT:5672}", "spring.rabbitmq.username=${IT_RABBITMQ_USERNAME}",
        "spring.rabbitmq.password=${IT_RABBITMQ_PASSWORD}", "spring.rabbitmq.virtual-host=${IT_RABBITMQ_VHOST:/}",
        "notificacoes.consumidor-email.enabled=false", "notificacoes.consumidor-interno.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=${IT_KEYCLOAK_URL}/realms/flowsla-ms-not-test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${IT_KEYCLOAK_URL}/realms/flowsla-ms-not-test/protocol/openid-connect/certs"
})
@Import(FluxoSeguroIT.Topologia.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class FluxoSeguroIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String FILA = SCHEMA + ".interna";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @DynamicPropertySource static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.flyway.default-schema", () -> SCHEMA);
        r.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        r.add("notificacoes.filas.interna", () -> FILA);
    }
    @TestConfiguration static class Topologia {
        @Bean Queue filaFuncional() { return new Queue(FILA, false, false, true); }
    }
    @Autowired RabbitTemplate rabbit;
    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired JwtDecoder decoder;
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filtros;

    @Test void eventoConsultadoELidoSomentePeloDestinatarioComTokenReal() throws Exception {
        MockMvc http = MockMvcBuilders.webAppContextSetup(context).addFilters(filtros).build();
        String tecnico = TokensKeycloak.obter("tecnico");
        String gestor = TokensKeycloak.obter("gestor");
        String cliente = TokensKeycloak.obter("cliente");
        String sub = decoder.decode(tecnico).getSubject();
        var evento = (ObjectNode) JSON.readTree(Files.readString(Path.of("docs/contratos/exemplos/validos/atribuicao.json")));
        evento.put("eventoId", UUID.randomUUID().toString());
        evento.putArray("destinatarios").add(sub);
        rabbit.send("", FILA, new Message(evento.toString().getBytes(StandardCharsets.UTF_8)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(notificacoes.count()).isEqualTo(1));
        String id = notificacoes.findAll().getFirst().getId().toString();
        http.perform(get("/notificacoes")).andExpect(status().isUnauthorized());
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + cliente)).andExpect(status().isForbidden());
        var lista = http.perform(get("/notificacoes?page=0&size=1").header("Authorization", "Bearer " + tecnico))
                .andExpect(status().isOk()).andReturn();
        var pagina = JSON.readTree(lista.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(pagina.get("totalElementos").asLong()).isEqualTo(1);
        assertThat(pagina.get("totalNaoLidas").asLong()).isEqualTo(1);
        assertThat(pagina.at("/itens/0/id").asText()).isEqualTo(id);
        var outro = http.perform(get("/notificacoes").param("sub", sub).header("Authorization", "Bearer " + gestor))
                .andExpect(status().isOk()).andReturn();
        assertThat(JSON.readTree(outro.getResponse().getContentAsString()).get("totalElementos").asLong()).isZero();
        http.perform(patch("/notificacoes/" + id + "/lida").header("Authorization", "Bearer " + gestor)).andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) http.perform(patch("/notificacoes/" + id + "/lida")
                .header("Authorization", "Bearer " + tecnico)).andExpect(status().isNoContent());
        var lida = http.perform(get("/notificacoes").header("Authorization", "Bearer " + tecnico)).andReturn();
        assertThat(JSON.readTree(lida.getResponse().getContentAsString()).get("totalNaoLidas").asLong()).isZero();
    }
}
