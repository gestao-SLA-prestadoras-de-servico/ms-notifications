package com.gestaosla.notificacoes.integracoes;

import com.gestaosla.notificacoes.config.SmtpProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class RecuperacaoSmtp {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecuperacaoSmtp.class);
    private final CircuitBreaker circuito;
    private final ProtecaoSmtp protecao;
    private final RabbitListenerEndpointRegistry registry;
    private final SmtpProperties properties;
    private final boolean consumidorHabilitado;
    private volatile boolean encerrando;
    private boolean pausadoPeloCircuito;

    public RecuperacaoSmtp(CircuitBreaker circuito, ProtecaoSmtp protecao,
            RabbitListenerEndpointRegistry registry, SmtpProperties properties,
            @Value("${notificacoes.consumidor-email.enabled}") boolean consumidorHabilitado,
            @Qualifier("smtpScheduler") ThreadPoolTaskScheduler scheduler) {
        this.circuito = circuito;
        this.protecao = protecao;
        this.registry = registry;
        this.properties = properties;
        this.consumidorHabilitado = consumidorHabilitado;
        circuito.getEventPublisher().onStateTransition(evento -> {
            LOGGER.info("Circuito SMTP: estado={}", evento.getStateTransition().getToState());
            // O stop aguarda a entrega atual; jamais executá-lo na thread do próprio listener.
            if (!encerrando) scheduler.execute(this::sincronizarConsumidor);
        });
    }

    @Scheduled(fixedDelayString = "${notificacoes.smtp.probe-interval}", scheduler = "smtpScheduler")
    public void verificarRecuperacao() {
        if (encerrando || !properties.enabled() || !consumidorHabilitado) return;
        sincronizarConsumidor();
        protecao.sondar();
        sincronizarConsumidor();
    }

    synchronized void sincronizarConsumidor() {
        if (encerrando || !properties.enabled() || !consumidorHabilitado) return;
        var container = registry.getListenerContainer("notificacoesEmail");
        if (container == null) return;
        if (circuito.getState() != CircuitBreaker.State.CLOSED) {
            if (container.isRunning()) {
                if (container instanceof AbstractMessageListenerContainer amqp) amqp.setForceStop(true);
                pausadoPeloCircuito = true;
                container.stop();
                LOGGER.warn("Consumidor de e-mail pausado; tentativas preservadas");
            }
        } else if (pausadoPeloCircuito) {
            container.start();
            pausadoPeloCircuito = false;
            LOGGER.info("Consumidor de e-mail retomado após recuperação SMTP");
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void encerrar() { encerrando = true; }
}
