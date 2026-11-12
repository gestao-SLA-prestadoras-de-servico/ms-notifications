package com.gestaosla.notificacoes.api;

import java.time.Instant;
import java.util.UUID;
import com.gestaosla.notificacoes.eventos.TipoEvento;
import com.gestaosla.notificacoes.persistencia.NotificacaoInterna;

public record NotificacaoResponse(UUID id, TipoEvento tipo, String chamadoId, String numeroChamado,
        String titulo, String mensagem, Instant criadoEm, Instant lidaEm) {
    static NotificacaoResponse de(NotificacaoInterna notificacao) {
        return new NotificacaoResponse(notificacao.getId(), notificacao.getTipo(), notificacao.getChamadoId(),
                notificacao.getNumeroChamado(), notificacao.getTitulo(), notificacao.getMensagem(),
                notificacao.getCriadoEm(), notificacao.getLidaEm());
    }
}
