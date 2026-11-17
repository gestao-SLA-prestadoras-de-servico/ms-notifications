package com.gestaosla.notificacoes.eventos;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Payload publicado pela outbox do app-principal. A validação é feita pelos JSON Schemas de
 * {@code contratos/notificacoes/} antes do mapeamento (ver {@link LeitorEventoNotificacao}).
 *
 * @param relogio só em VIOLACAO
 * @param textoPosicionamento só em POSICIONAMENTO
 * @param encerramentoPrevistoEm só em AVISO_ENCERRAMENTO
 */
public record EventoNotificacao(
        String eventoId,
        TipoEvento tipo,
        ChamadoEvento chamado,
        String titulo,
        List<String> destinatarios,
        Relogio relogio,
        String textoPosicionamento,
        OffsetDateTime encerramentoPrevistoEm) {

    // Evita exposição acidental de e-mail e texto fornecido pelo usuário em logs.
    @Override
    public String toString() {
        return "EventoNotificacao[tipo=" + tipo + "]";
    }
}
