package com.gestaosla.notificacoes.eventos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import com.gestaosla.notificacoes.persistencia.EventoProcessadoRepository;
import com.gestaosla.notificacoes.persistencia.NotificacaoInternaRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${IT_DB_URL}", "spring.datasource.username=${IT_DB_USERNAME}",
        "spring.datasource.password=${IT_DB_PASSWORD}", "spring.rabbitmq.host=${IT_RABBITMQ_HOST}",
        "spring.rabbitmq.port=${IT_RABBITMQ_PORT:5672}", "spring.rabbitmq.username=${IT_RABBITMQ_USERNAME}",
        "spring.rabbitmq.password=${IT_RABBITMQ_PASSWORD}", "spring.rabbitmq.virtual-host=${IT_RABBITMQ_VHOST:/}",
        "spring.mail.host=${IT_MAIL_HOST}", "spring.mail.port=${IT_MAIL_PORT:1025}",
        "spring.mail.properties.mail.smtp.connectiontimeout=500", "spring.mail.properties.mail.smtp.timeout=500",
        "notificacoes.consumidor-email.enabled=true", "notificacoes.consumidor-interno.enabled=true",
        "notificacoes.smtp.enabled=true", "notificacoes.smtp.window-size=1", "notificacoes.smtp.minimum-calls=1",
        "notificacoes.smtp.open-wait=200ms", "notificacoes.smtp.probe-interval=100ms",
        "spring.rabbitmq.listener.simple.concurrency=3", "spring.rabbitmq.listener.simple.prefetch=5"
})
@Import(RecuperacaoSmtpIT.Topologia.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class RecuperacaoSmtpIT {
    private static final String TOKEN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "it_" + TOKEN;
    private static final String EXCHANGE = "it.smtp." + TOKEN;
    private static final String EMAIL = EXCHANGE + ".email";
    private static final String INTERNA = EXCHANGE + ".interna";
    private static final String DLQ = EXCHANGE + ".dlq";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        properties.add("notificacoes.filas.email", () -> EMAIL);
        properties.add("notificacoes.filas.interna", () -> INTERNA);
        properties.add("notificacoes.retry.exchange", () -> EXCHANGE + ".retry");
        properties.add("notificacoes.retry.dlx", () -> EXCHANGE + ".dlx");
    }

    @TestConfiguration
    static class Topologia {
        @Bean Declarables retriesSmtp() { return TopologiaRetryTeste.criar(EXCHANGE, 200); }
        @Bean Declarables filasSmtp() {
            var exchange = new DirectExchange(EXCHANGE);
            var dlx = new DirectExchange(EXCHANGE + ".dlx");
            var email = QueueBuilder.durable(EMAIL).deadLetterExchange(dlx.getName()).deadLetterRoutingKey("email").build();
            var interna = QueueBuilder.durable(INTERNA).build();
            var dlq = QueueBuilder.durable(DLQ).build();
            return new Declarables(exchange, dlx, email, interna, dlq,
                    BindingBuilder.bind(email).to(exchange).with("email"),
                    BindingBuilder.bind(interna).to(exchange).with("interna"),
                    BindingBuilder.bind(dlq).to(dlx).with("email"));
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired CircuitBreaker circuito;
    @Autowired io.micrometer.core.instrument.MeterRegistry metricas;
    @Autowired EventoProcessadoRepository processados;
    @Autowired NotificacaoInternaRepository notificacoes;
    @MockitoSpyBean JavaMailSenderImpl sender;

    @Test
    void indisponibilidadePausaEmailPreservaTentativasIsolaInternaERecuperaAutomaticamente() throws Exception {
        int porta = sender.getPort();
        try {
            sender.setPort(1); // Recusa TCP real; envio e sonda usam o mesmo endpoint indisponível.
            publicar("resolucao", "email", "smtp-primeiro-" + TOKEN, 3);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
                assertThat(metricas.get("notificacoes.smtp.circuito.estado").tag("estado", "OPEN").gauge().value()).isEqualTo(1);
                assertThat(email().isRunning()).isFalse();
                assertThat(email().getActiveConsumerCount()).isZero();
                assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isEqualTo(1);
            });
            var original = rabbit.receive(EMAIL, 2000);
            assertThat(original).isNotNull();
            assertThat(original.getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(3);
            rabbit.send(EMAIL, original);
            for (int i = 0; i < 10; i++) publicar("resolucao", "email", "smtp-pendente-" + TOKEN + "-" + i, 4);
            publicar("atribuicao", "interna", "sino-" + TOKEN, 1);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(notificacoes.count()).isPositive();
                assertThat(processados.existsByEventoIdAndCanal("sino-" + TOKEN, CanalNotificacao.INTERNA)).isTrue();
            });
            await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(email().isRunning()).isFalse();
                assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isEqualTo(11);
                assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero();
                assertThat(processados.count()).isEqualTo(1);
                for (int etapa = 1; etapa <= 3; etapa++) {
                    assertThat(admin.getQueueInfo(EXCHANGE + ".retry.email." + etapa).getMessageCount()).isZero();
                }
            });
            verify(sender, atLeastOnce()).testConnection();
            // Simula reinício indevido do consumidor durante OPEN: gate impede qualquer envio.
            clearInvocations(sender);
            email().start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(email().isRunning()).isFalse();
                assertThat(email().getActiveConsumerCount()).isZero();
                assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isEqualTo(11);
            });
            verify(sender, never()).send(any(MimeMessage.class));
            var pendentes = new java.util.ArrayList<Message>();
            for (int i = 0; i < 11; i++) {
                var pendente = rabbit.receive(EMAIL, 2000);
                assertThat(pendente).isNotNull();
                String id = JSON.readTree(pendente.getBody()).get("eventoId").asText();
                assertThat(pendente.getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA))
                        .isEqualTo(id.contains("primeiro") ? 3 : 4);
                pendentes.add(pendente);
            }
            for (var pendente : pendentes) rabbit.send(EMAIL, pendente);
            sender.setPort(porta);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                assertThat(metricas.get("notificacoes.smtp.circuito.estado").tag("estado", "CLOSED").gauge().value()).isEqualTo(1);
                assertThat(email().isRunning()).isTrue();
                assertThat(processados.count()).isEqualTo(12);
                assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isZero();
                assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero();
            });
            verify(sender, times(11)).send(any(MimeMessage.class));
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String url = System.getenv().getOrDefault("IT_MAILPIT_URL", "http://localhost:8025")
                    + "/api/v1/search?query=subject:" + TOKEN;
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(response.body()).get("messages_count").asInt()).isEqualTo(11);
            });
        } finally {
            sender.setPort(porta);
        }
    }

    private SimpleMessageListenerContainer email() {
        return (SimpleMessageListenerContainer) registry.getListenerContainer("notificacoesEmail");
    }

    private void publicar(String fixture, String canal, String id, int tentativa) throws Exception {
        var evento = (ObjectNode) JSON.readTree(Files.readString(Path.of("docs/contratos/exemplos/validos/" + fixture + ".json")));
        evento.put("eventoId", id); evento.put("titulo", "Circuito " + TOKEN);
        evento.putArray("destinatarios").add(canal.equals("email") ? "smtp-" + TOKEN + "@example.com" : "gestor-" + TOKEN);
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(ProcessadorEntrega.TENTATIVA, tentativa);
        rabbit.send(EXCHANGE, canal, new Message(JSON.writeValueAsBytes(evento), properties));
    }
}
