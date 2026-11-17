package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PublicadorEncaminhamento {
    private final RabbitTemplate template;
    private final long timeoutMs;

    public PublicadorEncaminhamento(ConnectionFactory connectionFactory,
            @Value("${notificacoes.retry.confirm-timeout-ms:5000}") long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Timeout de confirmação deve ser positivo");
        this.template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        // O returned permanece em CorrelationData, sem logar o corpo da mensagem.
        template.setReturnsCallback(returned -> { });
        template.setUsePublisherConnection(true);
        this.timeoutMs = timeoutMs;
    }

    public void publicar(String exchange, String routingKey, Message mensagem) throws IOException {
        var correlation = new CorrelationData();
        try {
            template.send(exchange, routingKey, mensagem, correlation);
            var confirm = correlation.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IOException("Encaminhamento não confirmado ou sem rota");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Encaminhamento interrompido", ex);
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            throw new IOException("Falha ao confirmar encaminhamento", ex);
        }
    }
}
