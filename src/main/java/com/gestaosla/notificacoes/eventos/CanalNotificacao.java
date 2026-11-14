package com.gestaosla.notificacoes.eventos;

public enum CanalNotificacao {
    EMAIL("email"), INTERNA("interna");

    private final String routingKey;

    CanalNotificacao(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
