package com.gestaosla.notificacoes.eventos;

import java.io.IOException;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorNotificacaoInterna {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumidorNotificacaoInterna.class);
    private final ProcessadorMensagemNotificacao processador;
    private final PersistidorNotificacaoInterna persistidor;

    public ConsumidorNotificacaoInterna(ProcessadorMensagemNotificacao processador, PersistidorNotificacaoInterna persistidor) {
        this.processador = processador;
        this.persistidor = persistidor;
    }

    @RabbitListener(id = "notificacoesInternas", queues = "${notificacoes.filas.interna}",
            ackMode = "MANUAL", autoStartup = "${notificacoes.consumidor-interno.enabled}")
    public void consumir(Message mensagem, Channel channel) throws IOException {
        try {
            processador.processar(mensagem, channel, CanalNotificacao.INTERNA, persistidor::persistir);
        } catch (EventoInvalidoException ex) {
            channel.basicReject(mensagem.getMessageProperties().getDeliveryTag(), false);
            LOGGER.warn("Mensagem interna inválida rejeitada sem requeue");
        } catch (RuntimeException ex) {
            // A transação já foi revertida. A política limitada de retries será adicionada na MS-NOT-08.
            channel.basicNack(mensagem.getMessageProperties().getDeliveryTag(), false, true);
            LOGGER.warn("Falha no consumo interno; reentrega solicitada ({})", ex.getClass().getSimpleName());
        }
    }
}
