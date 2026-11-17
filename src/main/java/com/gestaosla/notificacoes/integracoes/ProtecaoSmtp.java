package com.gestaosla.notificacoes.integracoes;

import java.util.concurrent.TimeUnit;
import com.gestaosla.notificacoes.config.SmtpProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Component;

@Component
public class ProtecaoSmtp {
    private final CircuitBreaker circuito;
    private final SmtpProperties properties;
    private final SondaSmtp sonda;

    public ProtecaoSmtp(CircuitBreaker circuito, SmtpProperties properties, SondaSmtp sonda) {
        this.circuito = circuito;
        this.properties = properties;
        this.sonda = sonda;
    }

    public boolean consumoPermitido() {
        return !properties.enabled() || circuito.getState() == CircuitBreaker.State.CLOSED;
    }

    public synchronized void executar(Runnable envio) {
        if (!properties.enabled()) { envio.run(); return; }
        // Somente a sonda recebe permissão em HALF_OPEN, nunca uma entrega da fila.
        if (!consumoPermitido()) throw new SmtpAguardandoRecuperacaoException();
        try {
            circuito.executeRunnable(envio);
        } catch (RuntimeException falha) {
            if (circuito.getState() != CircuitBreaker.State.CLOSED) throw new SmtpAguardandoRecuperacaoException();
            throw falha;
        }
    }

    public synchronized void sondar() {
        if (!properties.enabled() || circuito.getState() == CircuitBreaker.State.CLOSED
                || !circuito.tryAcquirePermission()) return;
        long inicio = System.nanoTime();
        try {
            sonda.verificar();
            circuito.onSuccess(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        } catch (Exception falha) {
            // Mesmo erro definitivo na conexão (ex.: credencial/TLS) impede recuperar o serviço.
            // Não classificá-lo como sucesso/ignorado em HALF_OPEN, nem liberar entregas.
            circuito.releasePermission();
            circuito.transitionToOpenState();
        }
    }
}
