package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.gestaosla.notificacoes.persistencia.*;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
class ConfirmacaoRabbitMQIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired ProcessadorMensagemNotificacao processor;
    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired EventoProcessadoRepository processados;
    private Connection connection;
    private Channel channel;
    private String queue;
    private byte[] payload;
    private String eventoId;

    @BeforeEach
    void preparar() throws Exception {
        notificacoes.deleteAll();
        processados.deleteAll();
        var factory = new ConnectionFactory();
        factory.setHost(System.getenv("IT_RABBITMQ_HOST"));
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("IT_RABBITMQ_PORT", "5672")));
        factory.setUsername(System.getenv("IT_RABBITMQ_USERNAME"));
        factory.setPassword(System.getenv("IT_RABBITMQ_PASSWORD"));
        factory.setVirtualHost(System.getenv().getOrDefault("IT_RABBITMQ_VHOST", "/"));
        factory.setAutomaticRecoveryEnabled(false);
        connection = factory.newConnection();
        channel = connection.createChannel();
        queue = channel.queueDeclare("", false, true, false, null).getQueue();
        eventoId = "ack-" + UUID.randomUUID();
        payload = Files.readString(Path.of("docs/contratos/exemplos/validos/atribuicao.json"))
                .replace("evento-1", eventoId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        channel.confirmSelect();
        channel.basicPublish("", queue, MessageProperties.PERSISTENT_BASIC, payload);
        channel.waitForConfirmsOrDie(5000);
    }

    @AfterEach
    void fechar() throws Exception {
        if (connection != null && connection.isOpen()) connection.close();
    }

    @Test
    void ackRealAconteceDepoisDoCommitVisivelEmOutraTransacao() throws Exception {
        var delivery = receber(channel);
        Channel observed = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[] { Channel.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("basicAck")) {
                        // Repositórios abrem transações novas após o retorno do coordenador.
                        assertThat(processados.existsByEventoIdAndCanal(eventoId, CanalNotificacao.INTERNA)).isTrue();
                        assertThat(notificacoes.count()).isEqualTo(1);
                    }
                    try { return method.invoke(channel, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
        assertThat(processor.processar(message(delivery), observed, CanalNotificacao.INTERNA, this::gravar))
                .isEqualTo(ResultadoProcessamento.PROCESSADO);
        assertThat(channel.basicGet(queue, false)).isNull();
    }

    @Test
    void perdaDoAckAposCommitProvocaReentregaSemDuplicar() throws Exception {
        var delivery = receber(channel);
        var broken = mock(Channel.class);
        doThrow(new IOException("ACK indisponível")).when(broken).basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        assertThatThrownBy(() -> processor.processar(message(delivery), broken, CanalNotificacao.INTERNA, this::gravar))
                .isInstanceOf(IOException.class);
        assertThat(processados.count()).isEqualTo(1);
        channel.close(); // O ACK simulado falhou; fechar o canal real devolve a entrega ao broker.
        channel = connection.createChannel();
        var redelivery = receber(channel);
        assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
        assertThat(processor.processar(message(redelivery), channel, CanalNotificacao.INTERNA, e -> fail("Não repetir")))
                .isEqualTo(ResultadoProcessamento.JA_PROCESSADO);
        assertThat(notificacoes.count()).isEqualTo(1);
        assertThat(channel.basicGet(queue, false)).isNull();
    }

    @Test
    void falhaAntesDoCommitPermiteReentregaEProcessamentoCompleto() throws Exception {
        var attempts = new AtomicInteger();
        var delivery = receber(channel);
        assertThatThrownBy(() -> processor.processar(message(delivery), channel, CanalNotificacao.INTERNA, e -> {
            gravar(e);
            attempts.incrementAndGet();
            throw new IllegalStateException("Falha simulada");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isZero();
        channel.close();
        channel = connection.createChannel();
        var redelivery = receber(channel);
        assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
        processor.processar(message(redelivery), channel, CanalNotificacao.INTERNA, e -> { attempts.incrementAndGet(); gravar(e); });
        assertThat(attempts).hasValue(2);
        assertThat(notificacoes.count()).isEqualTo(1);
        assertThat(processados.count()).isEqualTo(1);
        assertThat(channel.basicGet(queue, false)).isNull();
    }

    private void gravar(EventoNotificacao event) {
        notificacoes.saveAndFlush(new NotificacaoInterna(event.destinatarios().getFirst(), event.eventoId(), event.tipo(),
                event.chamado().id(), event.chamado().numero(), event.titulo(), Instant.parse("2026-11-11T13:00:00Z")));
    }

    private GetResponse receber(Channel from) {
        var result = new java.util.concurrent.atomic.AtomicReference<GetResponse>();
        await().atMost(Duration.ofSeconds(5)).until(() -> { result.set(from.basicGet(queue, false)); return result.get() != null; });
        return result.get();
    }

    private org.springframework.amqp.core.Message message(GetResponse delivery) {
        var properties = new org.springframework.amqp.core.MessageProperties();
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        return new org.springframework.amqp.core.Message(delivery.getBody(), properties);
    }
}
