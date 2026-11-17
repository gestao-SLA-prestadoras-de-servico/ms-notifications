package com.gestaosla.notificacoes.eventos;

import java.util.Locale;

/** Tipos de {@code outbox.api.EventoNotificacao.Tipo} do app-principal. A reatribuição chega como ATRIBUICAO + REMOCAO. */
public enum TipoEvento {
    ATRIBUICAO(CanalNotificacao.INTERNA),
    REMOCAO(CanalNotificacao.INTERNA),
    REABERTURA(CanalNotificacao.INTERNA),
    CONCLUSAO(CanalNotificacao.INTERNA),
    VIOLACAO(CanalNotificacao.INTERNA),
    POSICIONAMENTO(CanalNotificacao.EMAIL),
    RESOLUCAO(CanalNotificacao.EMAIL),
    AVISO_ENCERRAMENTO(CanalNotificacao.EMAIL);

    private final CanalNotificacao canal;

    TipoEvento(CanalNotificacao canal) {
        this.canal = canal;
    }

    public CanalNotificacao canal() {
        return canal;
    }

    /** Arquivo do schema em {@code contratos/notificacoes/}, com o mesmo nome do backend. */
    String arquivoSchema() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-') + ".schema.json";
    }
}
