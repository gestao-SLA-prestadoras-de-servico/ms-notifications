package com.gestaosla.notificacoes.persistencia;

import java.time.Instant;
import java.util.UUID;

import com.gestaosla.notificacoes.eventos.CanalNotificacao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Marcador de sucesso do evento inteiro em um canal; nunca contém o payload de e-mail. */
@Entity
@Table(name = "evento_processado")
public class EventoProcessado {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "evento_id", nullable = false, columnDefinition = "text", updatable = false)
    private String eventoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private CanalNotificacao canal;

    @Column(name = "processado_em", nullable = false, updatable = false)
    private Instant processadoEm;

    protected EventoProcessado() { }

    public EventoProcessado(String eventoId, CanalNotificacao canal, Instant processadoEm) {
        this.eventoId = eventoId;
        this.canal = canal;
        this.processadoEm = processadoEm;
    }

    public UUID getId() { return id; }
    public String getEventoId() { return eventoId; }
    public CanalNotificacao getCanal() { return canal; }
    public Instant getProcessadoEm() { return processadoEm; }
}
