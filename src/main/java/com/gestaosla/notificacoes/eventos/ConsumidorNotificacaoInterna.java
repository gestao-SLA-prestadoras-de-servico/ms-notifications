package com.gestaosla.notificacoes.eventos;

import java.io.IOException;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorNotificacaoInterna {
    private final ProcessadorEntrega processador;
    private final PersistidorNotificacaoInterna persistidor;

    public ConsumidorNotificacaoInterna(ProcessadorEntrega processador, PersistidorNotificacaoInterna persistidor) {
        this.processador = processador;
        this.persistidor = persistidor;
    }

    @RabbitListener(id = "notificacoesInternas", queues = "${notificacoes.filas.interna}",
            ackMode = "MANUAL", autoStartup = "${notificacoes.consumidor-interno.enabled}")
    public void consumir(Message mensagem, Channel channel) throws IOException {
        processador.consumir(mensagem, channel, CanalNotificacao.INTERNA, persistidor::persistir);
    }
}
