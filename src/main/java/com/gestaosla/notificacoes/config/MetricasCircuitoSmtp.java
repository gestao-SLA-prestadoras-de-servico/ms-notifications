package com.gestaosla.notificacoes.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MetricasCircuitoSmtp {
    private static final Logger LOG = LoggerFactory.getLogger(MetricasCircuitoSmtp.class);
    private final ObjectProvider<CircuitBreaker> circuito;
    private final ObjectProvider<SmtpProperties> propriedades;
    private final MeterRegistry registry;

    public MetricasCircuitoSmtp(@Qualifier("smtpCircuitBreaker") ObjectProvider<CircuitBreaker> circuito,
            ObjectProvider<SmtpProperties> propriedades, MeterRegistry registry) {
        this.circuito = circuito;
        this.propriedades = propriedades;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registrar() {
        CircuitBreaker breaker = circuito.getIfAvailable();
        SmtpProperties config = propriedades.getIfAvailable();
        if (breaker == null || config == null) {
            LOG.warn("Circuit breaker SMTP indisponível nesta versão; métrica de estado não registrada");
            return;
        }
        Gauge.builder("notificacoes.smtp.circuito.habilitado", config, p -> p.enabled() ? 1.0 : 0.0).register(registry);
        if (!config.enabled()) return;
        breaker.getEventPublisher().onStateTransition(evento -> registry.counter("notificacoes.smtp.circuito.transicoes",
                "de", evento.getStateTransition().getFromState().name(),
                "para", evento.getStateTransition().getToState().name()).increment());
        for (CircuitBreaker.State estado : CircuitBreaker.State.values()) {
            Gauge.builder("notificacoes.smtp.circuito.estado", breaker,
                    cb -> cb.getState() == estado ? 1.0 : 0.0)
                    .tag("estado", estado.name()).register(registry);
        }
    }
}
