package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.util.function.Consumer;
import io.micrometer.core.instrument.MeterRegistry;
import com.gestaosla.notificacoes.integracoes.SmtpAguardandoRecuperacaoException;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProcessadorEntrega {
    public static final String TENTATIVA = "x-notificacoes-tentativa";
    public static final String CATEGORIA = "x-notificacoes-categoria";
    public static final int MAX_TENTATIVAS = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessadorEntrega.class);
    private final ProcessadorMensagemNotificacao processador;
    private final ClassificadorFalha classificador;
    private final PublicadorEncaminhamento publicador;
    private final String retryExchange;
    private final String dlx;
    private final MeterRegistry metricas;

    public ProcessadorEntrega(ProcessadorMensagemNotificacao processador, ClassificadorFalha classificador,
            PublicadorEncaminhamento publicador,
            @Value("${notificacoes.retry.exchange:notificacoes.retry}") String retryExchange,
            @Value("${notificacoes.retry.dlx:notificacoes.dlx}") String dlx, MeterRegistry metricas) {
        this.processador = processador;
        this.classificador = classificador;
        this.publicador = publicador;
        this.retryExchange = retryExchange;
        this.dlx = dlx;
        this.metricas = metricas;
    }

    public void consumir(Message mensagem, Channel channel, CanalNotificacao canal,
            Consumer<EventoNotificacao> acao) throws IOException {
        int tentativa = 1;
        RuntimeException falha;
        try {
            tentativa = tentativa(mensagem);
            var resultado = processador.processar(mensagem, channel, canal, acao);
            metricas.counter("notificacoes.entregas", "canal", canal.routingKey(), "resultado",
                    resultado == ResultadoProcessamento.PROCESSADO ? "processado" : "duplicado").increment();
            return;
        } catch (RuntimeException ex) {
            if (canal == CanalNotificacao.EMAIL && ex instanceof SmtpAguardandoRecuperacaoException) {
                metricas.counter("notificacoes.falhas", "canal", canal.routingKey(), "categoria", "indisponibilidade_smtp").increment();
                channel.basicNack(mensagem.getMessageProperties().getDeliveryTag(), false, true);
                return;
            }
            falha = ex;
        } catch (IOException ex) {
            metricas.counter("notificacoes.falhas", "canal", canal.routingKey(), "categoria", "transporte").increment();
            throw ex;
        }
        var natureza = classificador.classificar(falha);
        metricas.counter("notificacoes.falhas", "canal", canal.routingKey(), "categoria",
                natureza.name().toLowerCase(java.util.Locale.ROOT)).increment();
        boolean retry = natureza == ClassificadorFalha.Natureza.TEMPORARIA && tentativa < MAX_TENTATIVAS;
        var copia = MessageBuilder.fromClonedMessage(mensagem)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setExpiration(null)
                .setHeader(TENTATIVA, retry ? tentativa + 1 : tentativa)
                .setHeader(CATEGORIA, natureza.name()).build();
        try {
            publicador.publicar(retry ? retryExchange : dlx,
                    retry ? canal.routingKey() + "." + tentativa : canal.routingKey(), copia);
        } catch (IOException ex) {
            // Falha de transporte não é outra tentativa de negócio. A origem permanece recuperável.
            metricas.counter("notificacoes.falhas", "canal", canal.routingKey(), "categoria", "encaminhamento").increment();
            LOGGER.error("Encaminhamento indisponível; origem mantida: canal={}, tentativa={}", canal, tentativa);
            channel.basicNack(mensagem.getMessageProperties().getDeliveryTag(), false, true);
            return;
        }
        try {
            channel.basicAck(mensagem.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException ex) {
            metricas.counter("notificacoes.falhas", "canal", canal.routingKey(), "categoria", "transporte").increment();
            throw ex;
        }
        if (retry) {
            LOGGER.warn("Retry agendado: canal={}, tentativa={}", canal, tentativa + 1);
        } else {
            LOGGER.error("Falha enviada à DLQ: canal={}, tentativa={}, categoria={}", canal, tentativa, natureza);
        }
    }

    private int tentativa(Message mensagem) {
        Object raw = mensagem.getMessageProperties().getHeaders().get(TENTATIVA);
        if (raw == null) return 1;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            long valor = ((Number) raw).longValue();
            if (valor >= 1 && valor <= MAX_TENTATIVAS) return (int) valor;
        }
        throw new EventoInvalidoException();
    }
}
