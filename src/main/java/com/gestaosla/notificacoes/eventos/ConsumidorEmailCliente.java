package com.gestaosla.notificacoes.eventos;

import java.io.IOException;

import com.gestaosla.notificacoes.integracoes.EnviadorEmailCliente;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorEmailCliente {
    private final ProcessadorEntrega processador;
    private final EnviadorEmailCliente enviador;

    public ConsumidorEmailCliente(ProcessadorEntrega processador, EnviadorEmailCliente enviador) {
        this.processador = processador;
        this.enviador = enviador;
    }

    @RabbitListener(id = "notificacoesEmail", queues = "${notificacoes.filas.email}",
            ackMode = "MANUAL", autoStartup = "${notificacoes.consumidor-email.enabled}")
    public void consumir(Message mensagem, Channel channel) throws IOException {
        processador.consumir(mensagem, channel, CanalNotificacao.EMAIL, enviador::enviar);
    }
}
