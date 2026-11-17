package com.gestaosla.notificacoes.eventos;

public record ChamadoEvento(String id, long numero) {

    /** Número como o usuário o vê, ex.: {@code #1042}. */
    public String exibicao() {
        return "#" + numero;
    }
}
