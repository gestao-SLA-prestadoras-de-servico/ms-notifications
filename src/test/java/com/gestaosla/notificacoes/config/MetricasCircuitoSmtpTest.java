package com.gestaosla.notificacoes.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetricasCircuitoSmtpTest {
    @Test void estadoRealDoCircuitoEExpostoSemDadosPessoais() {
        var registry = new SimpleMeterRegistry();
        var breaker = CircuitBreaker.ofDefaults("smtp");
        @SuppressWarnings("unchecked") ObjectProvider<CircuitBreaker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(breaker);
        new MetricasCircuitoSmtp(provider, propriedades(true), registry).registrar();
        assertThat(registry.get("notificacoes.smtp.circuito.estado").tag("estado", "CLOSED").gauge().value()).isEqualTo(1);
        breaker.transitionToOpenState();
        assertThat(registry.get("notificacoes.smtp.circuito.estado").tag("estado", "OPEN").gauge().value()).isEqualTo(1);
        assertThat(registry.get("notificacoes.smtp.circuito.estado").tag("estado", "CLOSED").gauge().value()).isZero();
        assertThat(registry.get("notificacoes.smtp.circuito.transicoes").tags("de", "CLOSED", "para", "OPEN").counter().count()).isEqualTo(1);
    }

    @Test void ausenciaDoCircuitoNaoSimulaEstadoSaudavel() {
        var registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked") ObjectProvider<CircuitBreaker> provider = mock(ObjectProvider.class);
        new MetricasCircuitoSmtp(provider, propriedades(true), registry).registrar();
        assertThat(registry.find("notificacoes.smtp.circuito.estado").gauges()).isEmpty();
    }

    @Test void circuitoDesabilitadoNaoEInformadoComoFechado() {
        var registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked") ObjectProvider<CircuitBreaker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(CircuitBreaker.ofDefaults("smtp"));
        var props = propriedades(false);
        new MetricasCircuitoSmtp(provider, props, registry).registrar();
        assertThat(registry.get("notificacoes.smtp.circuito.habilitado").gauge().value()).isZero();
        assertThat(registry.find("notificacoes.smtp.circuito.estado").gauges()).isEmpty();
    }

    private ObjectProvider<SmtpProperties> propriedades(boolean enabled) {
        @SuppressWarnings("unchecked") ObjectProvider<SmtpProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SmtpProperties(enabled, 1, 1, 100,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(5)));
        return provider;
    }
}
