package com.gestaosla.notificacoes.eventos;

import java.net.ServerSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.gestaosla.notificacoes.persistencia.*;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.datasource.url=${IT_DB_URL}", "spring.datasource.username=${IT_DB_USERNAME}", "spring.datasource.password=${IT_DB_PASSWORD}",
        "spring.rabbitmq.host=${IT_RABBITMQ_HOST}", "spring.rabbitmq.port=${IT_RABBITMQ_PORT:5672}",
        "spring.rabbitmq.username=${IT_RABBITMQ_USERNAME}", "spring.rabbitmq.password=${IT_RABBITMQ_PASSWORD}",
        "spring.rabbitmq.virtual-host=${IT_RABBITMQ_VHOST:/}", "notificacoes.consumidor-email.enabled=false",
        "notificacoes.consumidor-interno.enabled=false", "spring.rabbitmq.listener.simple.prefetch=1"
})
@Import(RetryRabbitMQIT.Topologia.class)
@Timeout(40)
class RetryRabbitMQIT {
    static final String TOKEN = UUID.randomUUID().toString().replace("-", "");
    static final String SCHEMA = "it_" + TOKEN;
    static final String EXCHANGE = "it.retrytest." + TOKEN;
    static final String FILA = EXCHANGE + ".interna";
    static final String DLQ = FILA + ".dlq";
    static final Binding BINDING_DLQ = new Binding(DLQ, Binding.DestinationType.QUEUE, EXCHANGE + ".dlx", "interna", null);

    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry p) {
        p.add("spring.flyway.default-schema", () -> SCHEMA);
        p.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        p.add("notificacoes.retry.exchange", () -> EXCHANGE + ".retry");
        p.add("notificacoes.retry.dlx", () -> EXCHANGE + ".dlx");
        p.add("teste.fila", () -> FILA);
    }

    @TestConfiguration
    static class Topologia {
        @Bean Declarables filas() {
            var exchange = new DirectExchange(EXCHANGE, true, false);
            var dlx = new DirectExchange(EXCHANGE + ".dlx", true, false);
            var work = QueueBuilder.durable(FILA).build();
            var dead = QueueBuilder.durable(DLQ).ttl(604800000).build();
            return new Declarables(exchange, dlx, work, dead, BindingBuilder.bind(work).to(exchange).with("interna"), BINDING_DLQ);
        }
        @Bean Declarables retries() { return TopologiaRetryTeste.criar(EXCHANGE, 200); }
        @Bean Acao acao(ProcessadorEntrega processor, NotificacaoInternaRepository repository) { return new Acao(processor, repository); }
    }

    static class Acao {
        final ProcessadorEntrega processor;
        final NotificacaoInternaRepository repository;
        final List<Long> instantes = new CopyOnWriteArrayList<>();
        final List<Integer> tentativas = new CopyOnWriteArrayList<>();
        volatile int falhas;
        volatile JavaMailSenderImpl smtp;
        Acao(ProcessadorEntrega processor, NotificacaoInternaRepository repository) { this.processor = processor; this.repository = repository; }

        @RabbitListener(id = "testeRetry", queues = "${teste.fila}", ackMode = "MANUAL", autoStartup = "false")
        public void consumir(Message message, Channel channel) throws IOException {
            processor.consumir(message, channel, CanalNotificacao.INTERNA, event -> {
                instantes.add(System.nanoTime());
                tentativas.add(((Number) message.getMessageProperties().getHeaders().getOrDefault(ProcessadorEntrega.TENTATIVA, 1)).intValue());
                repository.saveAndFlush(new NotificacaoInterna("sub", event.eventoId(), event.tipo(), "1042", "#1042", "Teste", Instant.EPOCH));
                if (smtp != null) {
                    var mail = smtp.createMimeMessage();
                    try { mail.setFrom("teste@example.com"); mail.setRecipients(jakarta.mail.Message.RecipientType.TO, "cliente@example.com"); mail.setText("Teste SMTP"); }
                    catch (jakarta.mail.MessagingException ex) { throw new IllegalStateException(ex); }
                    smtp.send(mail);
                }
                if (instantes.size() <= falhas) throw new QueryTimeoutException("Timeout simulado");
            });
        }
    }

    @Autowired Acao acao;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired EventoProcessadoRepository processados;
    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired PublicadorEncaminhamento publicador;

    @BeforeEach
    void preparar() {
        container().stop();
        admin.declareBinding(BINDING_DLQ);
        admin.purgeQueue(FILA, false); admin.purgeQueue(DLQ, false);
        for (String canal : new String[] {"interna", "email"}) for (int i = 1; i <= 3; i++) admin.purgeQueue(EXCHANGE + ".retry." + canal + "." + i, false);
        notificacoes.deleteAll(); processados.deleteAll();
        acao.instantes.clear(); acao.tentativas.clear(); acao.falhas = 0; acao.smtp = null;
        container().start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(container().getActiveConsumerCount()).isEqualTo(1));
    }

    @AfterEach void parar() { container().stop(); admin.declareBinding(BINDING_DLQ); }

    @Test
    void timeoutExecutaQuatroVezesComBackoffEPreservaCorpoNaDlq() throws Exception {
        acao.falhas = 99;
        byte[] body = publicar(false);
        var dead = aguardarDlq();
        assertThat(dead.getBody()).isEqualTo(body);
        assertThat(acao.tentativas).containsExactly(1, 2, 3, 4);
        for (int i = 1; i <= 3; i++) assertThat(acao.instantes.get(i) - acao.instantes.get(i - 1)).isGreaterThanOrEqualTo((long) (160_000_000 * (1 << (i - 1))));
        assertThat(dead.getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(4);
        assertThat(notificacoes.count()).isZero(); assertThat(processados.count()).isZero();
    }

    @Test
    void sucessoNaTerceiraTentativaConfirmaUmaNotificacaoEUmMarcador() throws Exception {
        acao.falhas = 2;
        publicar(false);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(processados.count()).isEqualTo(1));
        assertThat(acao.tentativas).containsExactly(1, 2, 3);
        assertThat(notificacoes.count()).isEqualTo(1);
        assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {451, 550})
    void respostaSmtpRealDefineRetryOuDlqImediata(int code) throws Exception {
        try (var server = new SmtpRejeitando(code)) {
            var smtp = new JavaMailSenderImpl(); smtp.setHost("127.0.0.1"); smtp.setPort(server.socket.getLocalPort());
            smtp.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
            smtp.getJavaMailProperties().setProperty("mail.smtp.timeout", "2000");
            acao.smtp = smtp;
            publicar(false);
            aguardarDlq();
            int expected = code == 451 ? 4 : 1;
            assertThat(acao.instantes).hasSize(expected);
            assertThat(server.recusados.get()).isEqualTo(expected);
            assertThat(processados.count()).isZero(); assertThat(notificacoes.count()).isZero();
        }
    }

    @Test
    void invalidaVaiDiretoParaDlqSemExecutarAcao() throws Exception {
        publicar(true);
        var dead = aguardarDlq();
        assertThat(acao.instantes).isEmpty();
        assertThat(dead.getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(1);
    }

    @Test
    void faltaDeBindingDaDlqMantemMensagemOriginalAteRotaSerRecuperada() throws Exception {
        // Inicializa também a conexão de publicação: RabbitAdmin declara beans ao abrir conexão.
        publicador.publicar(EXCHANGE + ".dlx", "interna", new Message(new byte[0]));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isEqualTo(1));
        rabbit.receive(DLQ);
        admin.removeBinding(BINDING_DLQ);
        byte[] body = publicar(true);
        // Dá tempo para uma publicação mandatory ser devolvida, sem confirmar a origem.
        await().pollDelay(Duration.ofMillis(250)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero());
        container().stop();
        // Cancelamento/NACK e contagem da fila são observados de forma assíncrona pelo broker.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(admin.getQueueInfo(FILA).getMessageCount()).isEqualTo(1);
            assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero();
        });
        assertThat(acao.instantes).isEmpty();
        admin.declareBinding(BINDING_DLQ);
        container().start();
        assertThat(aguardarDlq().getBody()).isEqualTo(body);
    }

    @Test
    void publicadorDetectaExchangeInexistente() {
        assertThatThrownBy(() -> publicador.publicar("ausente." + TOKEN, "interna", new Message(new byte[0]))).isInstanceOf(IOException.class);
    }

    @Test
    void numeroDaTentativaSobreviveAParadaDoConsumidor() throws Exception {
        acao.falhas = 1;
        publicar(false);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(acao.instantes).hasSize(1));
        container().stop();
        container().start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(processados.count()).isEqualTo(1));
        assertThat(acao.tentativas).containsExactly(1, 2);
    }

    private byte[] publicar(boolean invalid) throws Exception {
        var node = (ObjectNode) JsonMapper.builder().build().readTree(Files.readString(Path.of("docs/contratos/exemplos/validos/atribuicao.json")));
        node.put("eventoId", "retry-" + UUID.randomUUID());
        byte[] body = (invalid ? "{}" : node.toString()).getBytes(StandardCharsets.UTF_8);
        rabbit.send(EXCHANGE, "interna", new Message(body));
        return body;
    }

    private Message aguardarDlq() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isEqualTo(1));
        return rabbit.receive(DLQ);
    }

    private SimpleMessageListenerContainer container() { return (SimpleMessageListenerContainer) registry.getListenerContainer("testeRetry"); }

    static class SmtpRejeitando implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger recusados = new AtomicInteger();
        SmtpRejeitando(int code) throws IOException {
            socket = new ServerSocket(0);
            Thread.startVirtualThread(() -> {
                while (!socket.isClosed()) {
                    try {
                        var client = socket.accept();
                        Thread.startVirtualThread(() -> {
                            try (client; var input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                                 var output = new PrintWriter(client.getOutputStream(), true, StandardCharsets.US_ASCII)) {
                                output.print("220 teste SMTP\r\n"); output.flush();
                                for (String line; (line = input.readLine()) != null;) {
                                    if (line.startsWith("QUIT")) { output.print("221 Bye\r\n"); output.flush(); break; }
                                    if (line.startsWith("RCPT")) { recusados.incrementAndGet(); output.print(code + " Falha simulada\r\n"); }
                                    else output.print("250 OK\r\n");
                                    output.flush();
                                }
                            } catch (IOException ignored) { }
                        });
                    } catch (IOException ignored) { break; }
                }
            });
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
