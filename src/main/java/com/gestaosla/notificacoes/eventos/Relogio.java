package com.gestaosla.notificacoes.eventos;

/** Relógio de SLA violado, enviado no evento VIOLACAO (RN-23 a RN-25). */
public enum Relogio {
    RESPOSTA("resposta"),
    ATENDIMENTO_CAMPO("atendimento em campo"),
    RESOLUCAO("resolução");

    private final String descricao;

    Relogio(String descricao) {
        this.descricao = descricao;
    }

    public String descricao() {
        return descricao;
    }
}
