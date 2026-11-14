package com.gestaosla.notificacoes.eventos;

import java.io.IOException;

import com.gestaosla.notificacoes.integracoes.EnviadorEmailCliente;
import com.gestaosla.notificacoes.integracoes.ProtecaoSmtp;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorEmailCliente {
    private final ProcessadorEntrega processador;
    private final EnviadorEmailCliente enviador;
    private final ProtecaoSmtp protecao;

    public ConsumidorEmailCliente(ProcessadorEntrega processador, EnviadorEmailCliente enviador, ProtecaoSmtp protecao) {
        this.processador = processador;
        this.enviador = enviador;
        this.protecao = protecao;
    }

    @RabbitListener(id = "notificacoesEmail", queues = "${notificacoes.filas.email}",
            ackMode = "MANUAL", autoStartup = "${notificacoes.consumidor-email.enabled}")
    public void consumir(Message mensagem, Channel channel) throws IOException {
        if (!protecao.consumoPermitido()) {
            channel.basicNack(mensagem.getMessageProperties().getDeliveryTag(), false, true);
            return;
        }
        processador.consumir(mensagem, channel, CanalNotificacao.EMAIL, enviador::enviar);
    }
}
