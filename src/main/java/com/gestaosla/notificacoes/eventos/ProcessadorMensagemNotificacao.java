package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** Fronteira de ACK manual, reutilizada pelos consumidores dos dois canais. */
@Component
public class ProcessadorMensagemNotificacao {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessadorMensagemNotificacao.class);
    private final LeitorEventoNotificacao leitor;
    private final ProcessadorEventoIdempotente processador;
    private final MeterRegistry metricas;

    public ProcessadorMensagemNotificacao(LeitorEventoNotificacao leitor, ProcessadorEventoIdempotente processador, MeterRegistry metricas) {
        this.leitor = leitor;
        this.processador = processador;
        this.metricas = metricas;
    }

    public ResultadoProcessamento processar(Message mensagem, Channel channel, CanalNotificacao canal,
            Consumer<EventoNotificacao> acao) throws IOException {
        Objects.requireNonNull(channel, "channel obrigatório");
        Objects.requireNonNull(acao, "ação obrigatória");
        var evento = leitor.ler(new String(mensagem.getBody(), StandardCharsets.UTF_8), canal);
        ResultadoProcessamento resultado;
        try {
            resultado = processador.processar(evento.eventoId(), canal, () -> acao.accept(evento));
        } catch (RuntimeException ex) {
            LOGGER.warn("Falha no evento: evento_hash={}, canal={}", hash(evento.eventoId()), canal);
            throw ex;
        }
        if (resultado == ResultadoProcessamento.PROCESSADO) {
            metricas.counter("notificacoes.eventos.processados", "canal", canal.routingKey()).increment();
        }
        LOGGER.info("Evento processado: evento_hash={}, canal={}, resultado={}", hash(evento.eventoId()), canal, resultado);
        // Confirma somente esta entrega, no canal de origem, após o commit ou descarte da repetição.
        try {
            channel.basicAck(mensagem.getMessageProperties().getDeliveryTag(), false);
        } catch (RuntimeException ex) {
            throw new IOException("Falha ao confirmar entrega já processada", ex);
        }
        return resultado;
    }

    private static String hash(String eventoId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(eventoId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}
