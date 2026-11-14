package com.gestaosla.notificacoes.eventos;

import jakarta.validation.constraints.NotBlank;

public record ChamadoEvento(@NotBlank String id, @NotBlank String numero) {
}
