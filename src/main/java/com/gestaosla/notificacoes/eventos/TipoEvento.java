package com.gestaosla.notificacoes.eventos;

public enum TipoEvento {
    ATRIBUICAO(CanalNotificacao.INTERNA),
    REATRIBUICAO(CanalNotificacao.INTERNA),
    REMOCAO(CanalNotificacao.INTERNA),
    REABERTURA(CanalNotificacao.INTERNA),
    CONCLUSAO(CanalNotificacao.INTERNA),
    VIOLACAO_SLA(CanalNotificacao.INTERNA),
    PRIMEIRO_POSICIONAMENTO(CanalNotificacao.EMAIL),
    RESOLUCAO(CanalNotificacao.EMAIL),
    AVISO_ENCERRAMENTO(CanalNotificacao.EMAIL);

    private final CanalNotificacao canal;

    TipoEvento(CanalNotificacao canal) {
        this.canal = canal;
    }

    public CanalNotificacao canal() {
        return canal;
    }
}
