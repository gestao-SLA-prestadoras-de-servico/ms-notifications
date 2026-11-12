package com.gestaosla.notificacoes.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notificacoes.smtp")
public record SmtpProperties(boolean enabled, int windowSize, int minimumCalls, float failureRate,
        Duration openWait, Duration probeInterval) {
    public SmtpProperties {
        if (windowSize < 1 || minimumCalls < 1 || minimumCalls > windowSize
                || !Float.isFinite(failureRate) || failureRate <= 0 || failureRate > 100
                || openWait == null || openWait.isNegative() || openWait.isZero()
                || probeInterval == null || probeInterval.isNegative() || probeInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Parâmetros inválidos da proteção SMTP");
        }
    }
}
