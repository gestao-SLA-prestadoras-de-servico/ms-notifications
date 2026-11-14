package com.gestaosla.notificacoes.api;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notificacoes")
@PreAuthorize("hasAnyRole('TECNICO', 'GESTOR')")
public class NotificacoesController {
    private final NotificacoesService service;

    public NotificacoesController(NotificacoesService service) { this.service = service; }

    @GetMapping
    public PaginaNotificacoesResponse listar(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listar(page, size);
    }

    @PatchMapping("/{id}/lida")
    public ResponseEntity<Void> marcarComoLida(@PathVariable UUID id) {
        service.marcarComoLida(id);
        return ResponseEntity.noContent().build();
    }
}
