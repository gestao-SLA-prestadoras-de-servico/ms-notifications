package com.gestaosla.notificacoes.eventos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.gestaosla.notificacoes.persistencia.EventoProcessadoRepository;
import com.gestaosla.notificacoes.persistencia.NotificacaoInternaRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "notificacoes.consumidor-email.enabled=false",
        "spring.datasource.url=${IT_DB_URL}", "spring.datasource.username=${IT_DB_USERNAME}",
        "spring.datasource.password=${IT_DB_PASSWORD}", "spring.rabbitmq.host=${IT_RABBITMQ_HOST}",
        "spring.rabbitmq.port=${IT_RABBITMQ_PORT:5672}", "spring.rabbitmq.username=${IT_RABBITMQ_USERNAME}",
        "spring.rabbitmq.password=${IT_RABBITMQ_PASSWORD}", "spring.rabbitmq.virtual-host=${IT_RABBITMQ_VHOST:/}",
        "notificacoes.consumidor-interno.enabled=true", "spring.rabbitmq.listener.simple.concurrency=3",
        "spring.rabbitmq.listener.simple.prefetch=1"
})
@Import(ConsumoInternoRabbitMQIT.TopologiaTeste.class)
@Timeout(40)
class ConsumoInternoRabbitMQIT {
    private static final String TOKEN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "it_" + TOKEN;
    private static final String EXCHANGE = "it." + TOKEN;
    private static final String INTERNA = EXCHANGE + ".interna";
    private static final String DLQ = INTERNA + ".dlq";
    private static final String EMAIL = EXCHANGE + ".email";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        properties.add("notificacoes.filas.interna", () -> INTERNA);
    }

    @TestConfiguration
    static class TopologiaTeste {
        @Bean
        Declarables filasDeTeste() {
            var exchange = new DirectExchange(EXCHANGE, false, false);
            var dlx = new DirectExchange(EXCHANGE + ".dlx", false, false);
            var interna = QueueBuilder.nonDurable(INTERNA).deadLetterExchange(dlx.getName()).deadLetterRoutingKey("interna").build();
            var dlq = QueueBuilder.nonDurable(DLQ).ttl(604800000).build();
            var email = QueueBuilder.nonDurable(EMAIL).build();
            return new Declarables(exchange, dlx, interna, dlq, email,
                    BindingBuilder.bind(interna).to(exchange).with("interna"),
                    BindingBuilder.bind(email).to(exchange).with("email"),
                    BindingBuilder.bind(dlq).to(dlx).with("interna"));
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired EventoProcessadoRepository processados;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        container().stop();
        admin.purgeQueue(INTERNA, false);
        admin.purgeQueue(DLQ, false);
        admin.purgeQueue(EMAIL, false);
        notificacoes.deleteAll();
        processados.deleteAll();
        container().start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(container().getActiveConsumerCount()).isEqualTo(3));
    }

    @AfterEach
    void aguardaEntregasEmAndamento() {
        container().stop();
    }

    @ParameterizedTest
    @EnumSource(value = TipoEvento.class, names = {"ATRIBUICAO", "REATRIBUICAO", "REMOCAO", "REABERTURA", "CONCLUSAO", "VIOLACAO_SLA"})
    void listenerRealPersisteCadaDestinatarioNaoLidoEComDadosDoEvento(TipoEvento tipo) throws Exception {
        var event = evento(tipo);
        publicar(event);
        int total = event.get("destinatarios").size();
        aguardarSucesso(total);
        var rows = notificacoes.findAll();
        assertThat(rows).extracting(n -> n.getDestinatarioSub()).containsExactlyInAnyOrderElementsOf(
                java.util.stream.StreamSupport.stream(event.get("destinatarios").spliterator(), false).map(n -> n.asText()).toList());
        assertThat(rows).allSatisfy(n -> {
            assertThat(n.getEventoId()).isEqualTo(event.get("eventoId").asText());
            assertThat(n.getTipo()).isEqualTo(tipo);
            assertThat(n.getChamadoId()).isEqualTo(event.get("chamado").get("id").asText());
            assertThat(n.getNumeroChamado()).isEqualTo(event.get("chamado").get("numero").asText());
            assertThat(n.getTitulo()).isEqualTo(event.get("titulo").asText());
            assertThat(n.getLidaEm()).isNull();
            assertThat(n.getCriadoEm()).isNotNull();
            assertThat(n.getMensagem()).contains(n.getNumeroChamado(), n.getTitulo());
        });
        assertThat(rows.stream().map(n -> n.getCriadoEm()).distinct().count()).isEqualTo(1);
        if (tipo == TipoEvento.REATRIBUICAO) {
            assertThat(rows).extracting(n -> n.getDestinatarioSub()).containsExactly("sub-tecnico-substituto");
        }
    }

    @Test
    void republicacoesConcorrentesNaoDuplicamNotificacoesOuSucesso() throws Exception {
        var event = evento(TipoEvento.VIOLACAO_SLA);
        for (int i = 0; i < 25; i++) publicar(event);
        aguardarSucesso(3);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.getQueueInfo(INTERNA).getMessageCount()).isZero());
        container().stop();
        assertThat(notificacoes.count()).isEqualTo(3);
        assertThat(processados.count()).isEqualTo(1);
    }

    @Test
    void destinatariosRepetidosNoPayloadGeramUmaNotificacaoPorSub() throws Exception {
        var event = evento(TipoEvento.ATRIBUICAO);
        event.putArray("destinatarios").add("sub-1").add("sub-1").add("sub-2");
        publicar(event);
        aguardarSucesso(2);
    }

    @Test
    void falhaNoSegundoDestinatarioReverteTudoEReentregaDepoisDaRecuperacao() throws Exception {
        jdbc.execute("create sequence tentativas");
        jdbc.execute("create function falhar_destinatario() returns trigger language plpgsql as $$ begin if new.destinatario_sub='sub-2' then perform nextval('tentativas'); raise exception 'Falha simulada'; end if; return new; end; $$");
        jdbc.execute("create trigger falhar_insert before insert on notificacao_interna for each row execute function falhar_destinatario()");
        try {
            var event = evento(TipoEvento.ATRIBUICAO);
            event.putArray("destinatarios").add("sub-1").add("sub-2");
            publicar(event);
            await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject("select is_called from tentativas", Boolean.class)));
            container().stop();
            assertThat(notificacoes.count()).isZero();
            assertThat(processados.count()).isZero();
            assertThat(admin.getQueueInfo(INTERNA).getMessageCount()).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger falhar_insert on notificacao_interna");
            jdbc.execute("drop function falhar_destinatario()");
            jdbc.execute("drop sequence tentativas");
        }
        container().start();
        aguardarSucesso(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "TIPO_DESCONHECIDO", "RESOLUCAO"})
    void mensagemInvalidaOuDeOutroCanalVaiParaDlqSemPersistencia(String caso) throws Exception {
        String body = switch (caso) {
            case "{}" -> caso;
            case "RESOLUCAO" -> evento(TipoEvento.RESOLUCAO).toString();
            default -> evento(TipoEvento.ATRIBUICAO).put("tipo", caso).toString();
        };
        rabbit.send(EXCHANGE, "interna", new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isEqualTo(1));
        Message dead = rabbit.receive(DLQ);
        assertThat(dead).isNotNull();
        assertThat(dead.getBody()).isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(dead.getMessageProperties().getXDeathHeader().getFirst().get("reason").toString()).isEqualTo("rejected");
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isZero();
    }

    @Test
    void consumidorInternoNaoConsomeFilaDeEmail() throws Exception {
        var body = evento(TipoEvento.RESOLUCAO).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        rabbit.send(EXCHANGE, "email", new Message(body));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isEqualTo(1));
        assertThat(rabbit.receive(EMAIL).getBody()).isEqualTo(body);
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isZero();
    }

    private ObjectNode evento(TipoEvento tipo) throws Exception {
        String fixture = tipo.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".json";
        var node = (ObjectNode) JSON.readTree(Files.readString(Path.of("docs/contratos/exemplos/validos", fixture)));
        node.put("eventoId", "consumo-" + UUID.randomUUID());
        return node;
    }

    private void publicar(ObjectNode event) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(EXCHANGE, "interna", new Message(event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), properties));
    }

    private void aguardarSucesso(long total) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(notificacoes.count()).isEqualTo(total);
            assertThat(processados.count()).isEqualTo(1);
        });
    }

    private SimpleMessageListenerContainer container() {
        return (SimpleMessageListenerContainer) registry.getListenerContainer("notificacoesInternas");
    }
}
