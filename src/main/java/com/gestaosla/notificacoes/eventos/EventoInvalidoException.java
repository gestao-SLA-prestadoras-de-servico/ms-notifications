package com.gestaosla.notificacoes.eventos;

/** Falha definitiva de contrato; não inclui o payload nem valores dos campos. */
public class EventoInvalidoException extends RuntimeException {
    public EventoInvalidoException() {
        super("Mensagem de notificação inválida");
    }
}
