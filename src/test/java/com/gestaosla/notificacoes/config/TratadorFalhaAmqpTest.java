package com.gestaosla.notificacoes.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import static org.assertj.core.api.Assertions.*;

class TratadorFalhaAmqpTest {
    @Test void fatalComXDeathMantemDescartePadraoSemImprimirConteudo() {
        var properties = new MessageProperties();
        properties.setHeader("x-death", java.util.List.of(java.util.Map.of("count", 1L, "reason", "rejected")));
        var message = new Message("dado privado".getBytes(StandardCharsets.UTF_8), properties);
        var fatal = new ListenerExecutionFailedException("erro privado", new MessageConversionException("erro privado"), message);
        assertThatThrownBy(() -> new TratadorFalhaAmqp().handleError(fatal))
                .isInstanceOf(org.springframework.amqp.ImmediateAcknowledgeAmqpException.class)
                .hasMessageNotContaining("privado");
    }

    @Test void naoRegistraPayloadNemExcecaoEConservaRejeicaoDeFalhaFatal() {
        var logger = (Logger) LoggerFactory.getLogger(TratadorFalhaAmqp.class);
        var captura = new ListAppender<ILoggingEvent>(); captura.start(); logger.addAppender(captura);
        try {
            var handler = new TratadorFalhaAmqp();
            var message = new Message("cliente@example.com texto-privado".getBytes(StandardCharsets.UTF_8), new MessageProperties());
            var temporaria = new ListenerExecutionFailedException("texto-privado", new IOException("cliente@example.com"), message);
            handler.handleError(temporaria);
            var fatal = new ListenerExecutionFailedException("texto-privado", new MessageConversionException("cliente@example.com"), message);
            assertThatThrownBy(() -> handler.handleError(fatal)).isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasMessageNotContaining("cliente@example.com").hasMessageNotContaining("texto-privado");
            assertThat(captura.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("cliente@example.com", "texto-privado");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(captura); captura.stop(); }
    }
}
