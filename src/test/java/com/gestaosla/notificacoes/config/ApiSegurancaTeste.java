package com.gestaosla.notificacoes.config;

import com.gestaosla.notificacoes.api.IdentidadeAutenticada;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Endpoints exclusivos do classpath de testes; a API de negócio pertence à MS-NOT-11. */
@TestConfiguration
@EnableWebMvc
@Import({SecurityConfig.class, IdentidadeAutenticada.class, ApiSegurancaTeste.Endpoint.class})
class ApiSegurancaTeste {
    @TestComponent
    @RestController
    static class Endpoint {
        private final IdentidadeAutenticada identidade;
        Endpoint(IdentidadeAutenticada identidade) { this.identidade = identidade; }
        @GetMapping("/notificacoes") String consultar() { return identidade.sub(); }
        @PatchMapping("/notificacoes/{id}/lida") ResponseEntity<Void> ler() { return ResponseEntity.noContent().build(); }
        @GetMapping("/actuator/health") String health() { return "UP"; }
        @GetMapping("/fora") String fora() { return "Não deve ser acessível"; }
    }
}
