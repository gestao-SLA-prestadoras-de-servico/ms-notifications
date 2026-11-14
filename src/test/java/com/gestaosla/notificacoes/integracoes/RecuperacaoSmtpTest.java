package com.gestaosla.notificacoes.integracoes;

import java.time.Duration;
import com.gestaosla.notificacoes.config.SmtpProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.mockito.Mockito.*;

class RecuperacaoSmtpTest {
    @Test
    void pausaSomenteEmailEReiniciaSomenteQuandoFechar() {
        var circuito = CircuitBreaker.ofDefaults("smtp");
        var registry = mock(RabbitListenerEndpointRegistry.class);
        var email = mock(SimpleMessageListenerContainer.class);
        when(registry.getListenerContainer("notificacoesEmail")).thenReturn(email);
        when(email.isRunning()).thenReturn(true);
        var protecao = mock(ProtecaoSmtp.class);
        var controle = controle(circuito, registry, protecao, true);
        circuito.transitionToOpenState();
        controle.sincronizarConsumidor();
        verify(email).setForceStop(true); verify(email).stop();
        when(email.isRunning()).thenReturn(false);
        circuito.transitionToHalfOpenState(); controle.sincronizarConsumidor();
        verify(email, never()).start();
        circuito.transitionToClosedState(); controle.sincronizarConsumidor();
        verify(email).start();
        verify(registry, never()).getListenerContainer("notificacoesInternas");
    }

    @Test
    void consumidorExplicitamenteDesabilitadoNaoESondadoOuIniciado() {
        var circuito = CircuitBreaker.ofDefaults("smtp");
        var registry = mock(RabbitListenerEndpointRegistry.class);
        var protecao = mock(ProtecaoSmtp.class);
        var controle = controle(circuito, registry, protecao, false);
        circuito.transitionToOpenState(); controle.verificarRecuperacao();
        circuito.transitionToClosedState(); controle.sincronizarConsumidor();
        verifyNoInteractions(registry, protecao);
    }

    @Test
    void encerramentoImpedeSondaERetomada() {
        var circuito = CircuitBreaker.ofDefaults("smtp");
        var registry = mock(RabbitListenerEndpointRegistry.class);
        var protecao = mock(ProtecaoSmtp.class);
        var controle = controle(circuito, registry, protecao, true);
        controle.encerrar(); circuito.transitionToOpenState(); controle.verificarRecuperacao();
        verifyNoInteractions(registry, protecao);
    }

    private RecuperacaoSmtp controle(CircuitBreaker circuito, RabbitListenerEndpointRegistry registry,
            ProtecaoSmtp protecao, boolean habilitado) {
        return new RecuperacaoSmtp(circuito, protecao, registry,
                new SmtpProperties(true, 1, 1, 100, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                habilitado, mock(ThreadPoolTaskScheduler.class));
    }
}
