package com.gestaosla.notificacoes.eventos;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record EventoNotificacao(
        @NotBlank String eventoId,
        @NotNull TipoEvento tipo,
        @NotNull @Valid ChamadoEvento chamado,
        @NotBlank String titulo,
        @NotEmpty List<@NotBlank @Email(groups = EmailCanal.class) String> destinatarios,
        String textoPosicionamento) {

    /** Grupo adicional aplicado pelo leitor apenas aos eventos de e-mail. */
    public interface EmailCanal { }

    @JsonIgnore
    @AssertTrue(message = "textoPosicionamento deve ser preenchido somente no primeiro posicionamento")
    public boolean isTextoPosicionamentoValido() {
        if (tipo == null) {
            return true; // @NotNull identifica a ausência do tipo.
        }
        return tipo == TipoEvento.PRIMEIRO_POSICIONAMENTO
                ? textoPosicionamento != null && !textoPosicionamento.isBlank()
                : textoPosicionamento == null;
    }

    // Evita exposição acidental de e-mail e texto fornecido pelo usuário em logs.
    @Override
    public String toString() {
        return "EventoNotificacao[tipo=" + tipo + "]";
    }
}
