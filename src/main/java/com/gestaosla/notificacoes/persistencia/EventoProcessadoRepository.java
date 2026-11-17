package com.gestaosla.notificacoes.persistencia;

import java.util.UUID;

import com.gestaosla.notificacoes.eventos.CanalNotificacao;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoProcessadoRepository extends JpaRepository<EventoProcessado, UUID> {
    boolean existsByEventoIdAndCanal(String eventoId, CanalNotificacao canal);
}
