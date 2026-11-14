package com.gestaosla.notificacoes.persistencia;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificacaoInternaRepository extends JpaRepository<NotificacaoInterna, UUID> {
    Optional<NotificacaoInterna> findByEventoIdAndDestinatarioSub(String eventoId, String destinatarioSub);

    Page<NotificacaoInterna> findByDestinatarioSubOrderByCriadoEmDescIdDesc(String destinatarioSub, Pageable pageable);

    long countByDestinatarioSubAndLidaEmIsNull(String destinatarioSub);

    // O próprio UPDATE valida o dono e preserva a primeira leitura, inclusive sob concorrência.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificacaoInterna n
               set n.lidaEm = coalesce(n.lidaEm, case when n.criadoEm > :agora then n.criadoEm else :agora end)
             where n.id = :id and n.destinatarioSub = :sub
            """)
    int marcarComoLida(@Param("id") UUID id, @Param("sub") String sub, @Param("agora") Instant agora);
}
