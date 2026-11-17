package com.gestaosla.notificacoes.api;

import java.util.List;

public record PaginaNotificacoesResponse(List<NotificacaoResponse> itens, int pagina, int tamanho,
        long totalElementos, int totalPaginas, long totalNaoLidas) { }
