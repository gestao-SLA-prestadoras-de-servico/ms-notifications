package com.gestaosla.notificacoes.config;

import com.gestaosla.notificacoes.eventos.ClassificadorFalha;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SmtpProperties.class)
public class SmtpConfig {
    @Bean
    CircuitBreaker smtpCircuitBreaker(SmtpProperties properties, ClassificadorFalha classificador) {
        return CircuitBreaker.of("smtp", CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.windowSize()).minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRate()).waitDurationInOpenState(properties.openWait())
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .ignoreException(falha -> !classificador.indisponibilidadeSmtp(falha)).build());
    }

    @Bean
    ThreadPoolTaskScheduler smtpScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("smtp-recuperacao-");
        return scheduler;
    }
}
