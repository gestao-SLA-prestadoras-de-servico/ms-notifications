package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** Fronteira de ACK manual, reutilizada pelos consumidores dos dois canais. */
@Component
public class ProcessadorMensagemNotificacao {
    private final LeitorEventoNotificacao leitor;
    private final ProcessadorEventoIdempotente processador;

    public ProcessadorMensagemNotificacao(LeitorEventoNotificacao leitor, ProcessadorEventoIdempotente processador) {
        this.leitor = leitor;
        this.processador = processador;
    }

    public ResultadoProcessamento processar(Message mensagem, Channel channel, CanalNotificacao canal,
            Consumer<EventoNotificacao> acao) throws IOException {
        Objects.requireNonNull(channel, "channel obrigatório");
        Objects.requireNonNull(acao, "ação obrigatória");
        var evento = leitor.ler(new String(mensagem.getBody(), StandardCharsets.UTF_8), canal);
        var resultado = processador.processar(evento.eventoId(), canal, () -> acao.accept(evento));
        // Confirma somente esta entrega, no canal de origem, após o commit ou descarte da repetição.
        try {
            channel.basicAck(mensagem.getMessageProperties().getDeliveryTag(), false);
        } catch (RuntimeException ex) {
            throw new IOException("Falha ao confirmar entrega já processada", ex);
        }
        return resultado;
    }
}
