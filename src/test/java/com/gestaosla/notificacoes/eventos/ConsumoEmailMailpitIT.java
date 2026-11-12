package com.gestaosla.notificacoes.eventos;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
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
        "notificacoes.email.remetente=notificacoes@example.com", "notificacoes.consumidor-email.enabled=true",
        "notificacoes.consumidor-interno.enabled=true", "spring.rabbitmq.listener.simple.concurrency=3",
        "spring.rabbitmq.listener.simple.prefetch=1"
})
@Import(ConsumoEmailMailpitIT.TopologiaTeste.class)
@Timeout(40)
class ConsumoEmailMailpitIT {
    private static final String TOKEN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "it_" + TOKEN;
    private static final String EXCHANGE = "it.email." + TOKEN;
    private static final String EMAIL = EXCHANGE + ".email";
    private static final String INTERNA = EXCHANGE + ".interna";
    private static final String DLQ = EMAIL + ".dlq";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        properties.add("notificacoes.filas.email", () -> EMAIL);
        properties.add("notificacoes.filas.interna", () -> INTERNA);
    }

    @TestConfiguration
    static class TopologiaTeste {
        @Bean
        Declarables filasEmailTeste() {
            var exchange = new DirectExchange(EXCHANGE, false, false);
            var dlx = new DirectExchange(EXCHANGE + ".dlx", false, false);
            var email = QueueBuilder.nonDurable(EMAIL).deadLetterExchange(dlx.getName()).deadLetterRoutingKey("email").build();
            var interna = QueueBuilder.nonDurable(INTERNA).build();
            var dlq = QueueBuilder.nonDurable(DLQ).ttl(604800000).build();
            return new Declarables(exchange, dlx, email, interna, dlq,
                    BindingBuilder.bind(email).to(exchange).with("email"),
                    BindingBuilder.bind(interna).to(exchange).with("interna"),
                    BindingBuilder.bind(dlq).to(dlx).with("email"));
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired EventoProcessadoRepository processados;
    @Autowired NotificacaoInternaRepository notificacoes;
    @MockitoSpyBean JavaMailSenderImpl sender;
    private String caso;
    private String eventoId;
    private String destinatario;
    private int smtpPort;

    @BeforeEach
    void preparar() {
        email().stop(); interna().stop();
        admin.purgeQueue(EMAIL, false); admin.purgeQueue(INTERNA, false); admin.purgeQueue(DLQ, false);
        notificacoes.deleteAll(); processados.deleteAll();
        caso = UUID.randomUUID().toString();
        eventoId = "email-" + caso;
        destinatario = "cliente-" + caso + "@example.com";
        smtpPort = sender.getPort();
        clearInvocations(sender);
        email().start(); interna().start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(email().getActiveConsumerCount()).isEqualTo(3);
            assertThat(interna().getActiveConsumerCount()).isEqualTo(3);
        });
    }

    @AfterEach
    void parar() {
        email().stop(); interna().stop();
        sender.setPort(smtpPort);
    }

    @ParameterizedTest
    @EnumSource(value = TipoEvento.class, names = {"PRIMEIRO_POSICIONAMENTO", "RESOLUCAO", "AVISO_ENCERRAMENTO"})
    void enviaTresTiposAoContatoComTextoHtmlEAssuntoCorrespondentes(TipoEvento tipo) throws Exception {
        var event = evento(tipo);
        publicar(event, "email");
        aguardarEnvio(1);
        var mail = capturados().getFirst();
        assertThat(mail.get("Subject").asText()).contains("#1042", event.get("titulo").asText());
        assertThat(mail.get("From").get("Address").asText()).isEqualTo("notificacoes@example.com");
        assertThat(mail.get("To").size()).isEqualTo(1);
        assertThat(mail.get("To").get(0).get("Address").asText()).isEqualTo(destinatario);
        assertThat(mail.get("Text").asText()).contains("#1042", event.get("titulo").asText());
        assertThat(mail.get("HTML").asText()).contains("#1042", event.get("titulo").asText());
        if (tipo == TipoEvento.PRIMEIRO_POSICIONAMENTO) {
            assertThat(mail.get("Text").asText()).contains(event.get("textoPosicionamento").asText());
            assertThat(mail.get("HTML").asText()).contains(event.get("textoPosicionamento").asText());
        }
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isEqualTo(1);
    }

    @Test
    void htmlCapturadoEscapaConteudoDoClienteEPreservaQuebras() throws Exception {
        var event = evento(TipoEvento.PRIMEIRO_POSICIONAMENTO);
        event.put("titulo", "<img src=x> & \"ação\" " + caso);
        event.put("textoPosicionamento", "Linha 1\n<script>alert('x')</script>");
        publicar(event, "email");
        aguardarEnvio(1);
        var mail = capturados().getFirst();
        assertThat(mail.get("HTML").asText()).contains("&lt;img src=x&gt;", "&amp;", "&quot;", "Linha 1<br>&lt;script&gt;")
                .doesNotContain("<script>", "<img");
        assertThat(mail.get("Text").asText()).contains("<img src=x>", "<script>alert('x')</script>");
    }

    @Test
    void contatosRecebemEnvelopesIndividuaisSemExporOutroEndereco() throws Exception {
        var event = evento(TipoEvento.RESOLUCAO);
        String outro = "outro-" + caso + "@example.com";
        event.putArray("destinatarios").add(destinatario).add(destinatario).add(outro);
        publicar(event, "email");
        aguardarEnvio(2);
        var addresses = new ArrayList<String>();
        for (var mail : capturados()) {
            assertThat(mail.get("To").size()).isEqualTo(1);
            addresses.add(mail.get("To").get(0).get("Address").asText());
        }
        assertThat(addresses).containsExactlyInAnyOrder(destinatario, outro);
        assertThat(processados.count()).isEqualTo(1);
    }

    @Test
    void republicacoesConcorrentesNaoReenviamEmailJaConcluido() throws Exception {
        var event = evento(TipoEvento.RESOLUCAO);
        for (int i = 0; i < 25; i++) publicar(event, "email");
        aguardarEnvio(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isZero());
        email().stop();
        assertThat(capturados()).hasSize(1);
        assertThat(processados.count()).isEqualTo(1);
        assertThat(notificacoes.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEM_TEXTO", "EMAIL_INVALIDO", "ATRIBUICAO"})
    void mensagemInvalidaOuInternaVaiParaDlqSemSmtp(String casoInvalido) throws Exception {
        var event = evento(TipoEvento.RESOLUCAO);
        switch (casoInvalido) {
            case "SEM_TEXTO" -> event.put("tipo", "PRIMEIRO_POSICIONAMENTO");
            case "EMAIL_INVALIDO" -> event.putArray("destinatarios").add("email-invalido");
            default -> event.put("tipo", "ATRIBUICAO");
        }
        publicar(event, "email");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isEqualTo(1));
        assertThat(rabbit.receive(DLQ).getMessageProperties().getXDeathHeader().getFirst().get("reason").toString()).isEqualTo("rejected");
        assertThat(capturados()).isEmpty();
        assertThat(processados.count()).isZero();
        verify(sender, never()).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    void smtpIndisponivelNaoGravaSucessoECanalInternoContinuaAteRecuperacao() throws Exception {
        sender.setPort(1); // Porta sem SMTP no container Mailpit; conexão real será recusada.
        publicar(evento(TipoEvento.RESOLUCAO), "email");
        await().atMost(Duration.ofSeconds(10)).until(() -> mockingDetails(sender).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("send")));
        var interno = evento(TipoEvento.ATRIBUICAO);
        interno.put("eventoId", "interna-" + caso);
        interno.putArray("destinatarios").add("sub-tecnico");
        publicar(interno, "interna");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(notificacoes.count()).isEqualTo(1);
            assertThat(processados.existsByEventoIdAndCanal("interna-" + caso, CanalNotificacao.INTERNA)).isTrue();
        });
        email().stop();
        assertThat(processados.existsByEventoIdAndCanal(eventoId, CanalNotificacao.EMAIL)).isFalse();
        assertThat(capturados()).isEmpty();
        assertThat(admin.getQueueInfo(EMAIL).getMessageCount()).isEqualTo(1);
        sender.setPort(smtpPort);
        email().start();
        aguardarEnvio(1);
        assertThat(notificacoes.count()).isEqualTo(1);
        assertThat(processados.count()).isEqualTo(2);
    }

    private ObjectNode evento(TipoEvento tipo) throws Exception {
        var name = tipo.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".json";
        var event = (ObjectNode) JSON.readTree(Files.readString(Path.of("docs/contratos/exemplos/validos", name)));
        event.put("eventoId", eventoId);
        event.put("titulo", "Equipamento parado " + caso);
        event.putArray("destinatarios").add(destinatario);
        return event;
    }

    private void publicar(ObjectNode event, String routingKey) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(EXCHANGE, routingKey, new Message(event.toString().getBytes(StandardCharsets.UTF_8), properties));
    }

    private void aguardarEnvio(int count) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(processados.existsByEventoIdAndCanal(eventoId, CanalNotificacao.EMAIL)).isTrue();
            assertThat(capturados()).hasSize(count);
        });
    }

    private List<JsonNode> capturados() throws Exception {
        var matches = api("/api/v1/search?query=" + URLEncoder.encode("subject:" + caso, StandardCharsets.UTF_8));
        var result = new ArrayList<JsonNode>();
        for (var match : matches.get("messages")) result.add(api("/api/v1/message/" + match.get("ID").asText()));
        return result;
    }

    private JsonNode api(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(System.getenv("IT_MAILPIT_URL") + path)).timeout(Duration.ofSeconds(5)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private SimpleMessageListenerContainer email() {
        return (SimpleMessageListenerContainer) registry.getListenerContainer("notificacoesEmail");
    }

    private SimpleMessageListenerContainer interna() {
        return (SimpleMessageListenerContainer) registry.getListenerContainer("notificacoesInternas");
    }
}
