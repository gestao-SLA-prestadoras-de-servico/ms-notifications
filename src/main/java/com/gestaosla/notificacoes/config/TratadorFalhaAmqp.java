package com.gestaosla.notificacoes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateAcknowledgeAmqpException;
import org.springframework.amqp.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.listener.ListenerExecutionFailedException;

/** Mantém a política padrão de rejeição sem registrar mensagem ou exceção do provedor. */
public class TratadorFalhaAmqp extends ConditionalRejectingErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TratadorFalhaAmqp.class);

    public TratadorFalhaAmqp() {
        super(new DefaultExceptionStrategy() {
            @Override protected void logFatalException(ListenerExecutionFailedException exception, Throwable cause) { }
        });
    }

    @Override
    public void handleError(Throwable falha) {
        LOG.error("Falha no consumidor AMQP; consulte o estado do canal e das dependências");
        if (!causeChainContainsARADRE(falha) && getExceptionStrategy().isFatal(falha)) {
            if (isDiscardFatalsWithXDeath() && falha instanceof ListenerExecutionFailedException lefe
                    && lefe.getFailedMessage() != null) {
                var deaths = lefe.getFailedMessage().getMessageProperties().getXDeathHeader();
                if (deaths != null && !deaths.isEmpty()) {
                    handleDiscarded(lefe.getFailedMessage());
                    throw new ImmediateAcknowledgeAmqpException("Falha fatal em mensagem anteriormente rejeitada");
                }
            }
            throw new AmqpRejectAndDontRequeueException("Falha fatal no consumidor AMQP", isRejectManual(), null);
        }
    }
}
