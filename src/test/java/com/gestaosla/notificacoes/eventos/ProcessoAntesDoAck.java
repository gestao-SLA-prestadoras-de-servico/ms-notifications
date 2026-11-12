package com.gestaosla.notificacoes.eventos;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import com.gestaosla.notificacoes.NotificacoesApplication;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.SpringApplication;

/** Processo descartável de teste: interrompe a JVM exatamente entre commit e ACK. */
public final class ProcessoAntesDoAck {
    public static void main(String[] args) throws Exception {
        try (var context = SpringApplication.run(NotificacoesApplication.class,
                "--server.port=0", "--notificacoes.consumidor-interno.enabled=false",
                "--notificacoes.consumidor-email.enabled=false", "--notificacoes.retencao.cron=-")) {
            var connection = context.getBean(ConnectionFactory.class).createConnection();
            Channel real = connection.createChannel(false);
            var delivery = real.basicGet("notificacoes.interna", false);
            if (delivery == null) throw new IllegalStateException("Entrega de teste ausente");
            Channel interrompido = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("basicAck")) Runtime.getRuntime().halt(37);
                        try { return method.invoke(real, arguments); }
                        catch (InvocationTargetException ex) { throw ex.getCause(); }
                    });
            var properties = new MessageProperties();
            properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
            context.getBean(ProcessadorMensagemNotificacao.class).processar(new Message(delivery.getBody(), properties),
                    interrompido, CanalNotificacao.INTERNA, context.getBean(PersistidorNotificacaoInterna.class)::persistir);
            throw new IllegalStateException("A JVM deveria ter sido interrompida antes do ACK");
        }
    }
}
