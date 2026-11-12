package com.gestaosla.notificacoes.eventos;

import java.io.IOException;

import com.gestaosla.notificacoes.integracoes.EnviadorEmailCliente;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorEmailCliente {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumidorEmailCliente.class);
    private final ProcessadorMensagemNotificacao processador;
    private final EnviadorEmailCliente enviador;

    public ConsumidorEmailCliente(ProcessadorMensagemNotificacao processador, EnviadorEmailCliente enviador) {
        this.processador = processador;
        this.enviador = enviador;
    }

    @RabbitListener(id = "notificacoesEmail", queues = "${notificacoes.filas.email}",
            ackMode = "MANUAL", autoStartup = "${notificacoes.consumidor-email.enabled}")
    public void consumir(Message mensagem, Channel channel) throws IOException {
        try {
            processador.processar(mensagem, channel, CanalNotificacao.EMAIL, enviador::enviar);
        } catch (EventoInvalidoException ex) {
            channel.basicReject(mensagem.getMessageProperties().getDeliveryTag(), false);
            LOGGER.warn("Mensagem de e-mail inválida rejeitada sem requeue");
        } catch (RuntimeException ex) {
            channel.basicNack(mensagem.getMessageProperties().getDeliveryTag(), false, true);
            LOGGER.warn("Falha no envio ao cliente; reentrega solicitada ({})", ex.getClass().getSimpleName());
        }
    }
}
