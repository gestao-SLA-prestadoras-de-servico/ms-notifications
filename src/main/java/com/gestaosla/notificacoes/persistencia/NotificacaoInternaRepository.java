package com.gestaosla.notificacoes.persistencia;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificacaoInternaRepository extends JpaRepository<NotificacaoInterna, UUID> {
    Optional<NotificacaoInterna> findByEventoIdAndDestinatarioSub(String eventoId, String destinatarioSub);

    Page<NotificacaoInterna> findByDestinatarioSubOrderByCriadoEmDescIdDesc(String destinatarioSub, Pageable pageable);

    long countByDestinatarioSubAndLidaEmIsNull(String destinatarioSub);
}
