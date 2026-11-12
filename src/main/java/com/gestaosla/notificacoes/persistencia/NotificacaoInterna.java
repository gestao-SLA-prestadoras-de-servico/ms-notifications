package com.gestaosla.notificacoes.persistencia;

import java.time.Instant;
import java.util.UUID;

import com.gestaosla.notificacoes.eventos.TipoEvento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notificacao_interna")
public class NotificacaoInterna {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "destinatario_sub", nullable = false, columnDefinition = "text", updatable = false)
    private String destinatarioSub;

    @Column(name = "evento_id", nullable = false, columnDefinition = "text", updatable = false)
    private String eventoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private TipoEvento tipo;

    @Column(name = "chamado_id", nullable = false, columnDefinition = "text", updatable = false)
    private String chamadoId;

    @Column(name = "numero_chamado", nullable = false, columnDefinition = "text", updatable = false)
    private String numeroChamado;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String titulo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "lida_em")
    private Instant lidaEm;

    protected NotificacaoInterna() { }

    public NotificacaoInterna(String destinatarioSub, String eventoId, TipoEvento tipo,
            String chamadoId, String numeroChamado, String titulo, Instant criadoEm) {
        this.destinatarioSub = destinatarioSub;
        this.eventoId = eventoId;
        this.tipo = tipo;
        this.chamadoId = chamadoId;
        this.numeroChamado = numeroChamado;
        this.titulo = titulo;
        this.criadoEm = criadoEm;
    }

    public UUID getId() { return id; }
    public String getDestinatarioSub() { return destinatarioSub; }
    public String getEventoId() { return eventoId; }
    public TipoEvento getTipo() { return tipo; }
    public String getChamadoId() { return chamadoId; }
    public String getNumeroChamado() { return numeroChamado; }
    public String getTitulo() { return titulo; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getLidaEm() { return lidaEm; }
}
