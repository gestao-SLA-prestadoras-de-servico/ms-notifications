package com.gestaosla.notificacoes.api;

import java.time.Clock;
import java.util.UUID;
import com.gestaosla.notificacoes.persistencia.NotificacaoInternaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificacoesService {
    private final NotificacaoInternaRepository repository;
    private final IdentidadeAutenticada identidade;
    private final Clock clock;

    public NotificacoesService(NotificacaoInternaRepository repository, IdentidadeAutenticada identidade, Clock clock) {
        this.repository = repository;
        this.identidade = identidade;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaginaNotificacoesResponse listar(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new PaginacaoInvalidaException();
        }
        String sub = identidade.sub();
        var pagina = repository.findByDestinatarioSubOrderByCriadoEmDescIdDesc(sub, PageRequest.of(page, size));
        return new PaginaNotificacoesResponse(pagina.map(NotificacaoResponse::de).getContent(), page, size,
                pagina.getTotalElements(), pagina.getTotalPages(), repository.countByDestinatarioSubAndLidaEmIsNull(sub));
    }

    @Transactional
    public void marcarComoLida(UUID id) {
        if (repository.marcarComoLida(id, identidade.sub(), clock.instant()) == 0) {
            throw new NotificacaoNaoEncontradaException();
        }
    }
}
