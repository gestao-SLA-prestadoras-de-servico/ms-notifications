package com.gestaosla.notificacoes.eventos;

import java.time.Clock;

import com.gestaosla.notificacoes.persistencia.NotificacaoInterna;
import com.gestaosla.notificacoes.persistencia.NotificacaoInternaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistidorNotificacaoInterna {
    private final NotificacaoInternaRepository repository;
    private final Clock clock;

    public PersistidorNotificacaoInterna(NotificacaoInternaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void persistir(EventoNotificacao evento) {
        if (evento.tipo().canal() != CanalNotificacao.INTERNA) {
            throw new EventoInvalidoException();
        }
        var criadoEm = clock.instant();
        var notificacoes = evento.destinatarios().stream().distinct()
                .map(sub -> new NotificacaoInterna(sub, evento.eventoId(), evento.tipo(),
                        evento.chamado().id(), evento.chamado().numero(), evento.titulo(), criadoEm))
                .toList();
        repository.saveAllAndFlush(notificacoes);
    }
}
