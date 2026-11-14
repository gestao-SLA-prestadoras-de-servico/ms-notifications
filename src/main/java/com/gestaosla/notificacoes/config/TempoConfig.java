package com.gestaosla.notificacoes.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TempoConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
