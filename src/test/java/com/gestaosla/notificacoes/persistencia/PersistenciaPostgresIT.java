package com.gestaosla.notificacoes.persistencia;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gestaosla.notificacoes.eventos.CanalNotificacao;
import com.gestaosla.notificacoes.eventos.TipoEvento;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executado explicitamente com -Ppostgres-it; exige PostgreSQL real via IT_DB_*. */
@SpringBootTest(properties = {
        "notificacoes.consumidor-interno.enabled=false",
        "spring.datasource.url=${IT_DB_URL}",
        "spring.datasource.username=${IT_DB_USERNAME}",
        "spring.datasource.password=${IT_DB_PASSWORD}",
        "spring.rabbitmq.username=test",
        "spring.rabbitmq.password=test"
})
class PersistenciaPostgresIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant CRIADO_EM = Instant.parse("2026-11-11T13:00:00.123456Z");

    @DynamicPropertySource
    static void schemaIsolado(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired NotificacaoInternaRepository notificacoes;
    @Autowired EventoProcessadoRepository processados;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired TransactionTemplate transaction;

    @BeforeEach
    void limpaSomenteOsRegistrosDoSchemaDeTeste() {
        notificacoes.deleteAll();
        processados.deleteAll();
    }

    @Test
    void migraSchemaVazioEValidaSemReaplicarAVersao() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version='1'", Long.class))
                .isEqualTo(1);
        var indices = jdbc.queryForList("select indexdef from pg_indexes where schemaname=? and tablename='notificacao_interna'", String.class, SCHEMA);
        assertThat(indices).anyMatch(sql -> sql.contains("(destinatario_sub, criado_em DESC, id DESC)"));
        assertThat(indices).anyMatch(sql -> sql.contains("(destinatario_sub) WHERE (lida_em IS NULL)"));
        assertThat(indices).anyMatch(sql -> sql.contains("(criado_em)"));
    }

    @Test
    void persisteTodosOsCamposSemTruncarTituloEComLeituraInicialNula() {
        String titulo = "Manutenção – equipamento #1042 ".repeat(30);
        var nova = notificacoes.saveAndFlush(new NotificacaoInterna("sub-tecnico", "sla:1042:ATENDIMENTO:marco",
                TipoEvento.VIOLACAO_SLA, "chamado-opaco", "#1042", titulo, CRIADO_EM));
        var salva = notificacoes.findById(nova.getId()).orElseThrow();
        assertThat(salva.getId()).isNotNull();
        assertThat(salva.getDestinatarioSub()).isEqualTo("sub-tecnico");
        assertThat(salva.getEventoId()).isEqualTo("sla:1042:ATENDIMENTO:marco");
        assertThat(salva.getTipo()).isEqualTo(TipoEvento.VIOLACAO_SLA);
        assertThat(salva.getChamadoId()).isEqualTo("chamado-opaco");
        assertThat(salva.getNumeroChamado()).isEqualTo("#1042");
        assertThat(salva.getTitulo()).isEqualTo(titulo);
        assertThat(salva.getCriadoEm()).isEqualTo(CRIADO_EM);
        assertThat(salva.getLidaEm()).isNull();
        jdbc.update("update notificacao_interna set lida_em=criado_em + interval '1 hour' where id=?", salva.getId());
        assertThat(notificacoes.findById(salva.getId()).orElseThrow().getLidaEm()).isEqualTo(CRIADO_EM.plusSeconds(3600));
    }

    @Test
    void permiteVariosDestinatariosMasRejeitaNotificacaoDuplicadaParaOMesmoEventoESub() {
        notificacoes.saveAndFlush(nova("evento-1", "sub-1", CRIADO_EM));
        notificacoes.saveAndFlush(nova("evento-1", "sub-2", CRIADO_EM));
        assertThat(notificacoes.findByEventoIdAndDestinatarioSub("evento-1", "sub-2")).isPresent();
        assertThatThrownBy(() -> notificacoes.saveAndFlush(nova("evento-1", "sub-1", CRIADO_EM)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(notificacoes.count()).isEqualTo(2);
    }

    @Test
    void sucessoEUnicoPorEventoECanalSemImpedirOutroCanal() {
        var interna = processados.saveAndFlush(new EventoProcessado("evento-1", CanalNotificacao.INTERNA, CRIADO_EM));
        processados.saveAndFlush(new EventoProcessado("evento-1", CanalNotificacao.EMAIL, CRIADO_EM));
        assertThat(processados.findById(interna.getId()).orElseThrow().getProcessadoEm()).isEqualTo(CRIADO_EM);
        assertThat(processados.existsByEventoIdAndCanal("evento-1", CanalNotificacao.INTERNA)).isTrue();
        assertThatThrownBy(() -> processados.saveAndFlush(new EventoProcessado("evento-1", CanalNotificacao.INTERNA, CRIADO_EM)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(processados.count()).isEqualTo(2);
    }

    @Test
    void exclusaoDaNotificacaoPreservaMarcadorDeSucesso() {
        var notificacao = notificacoes.saveAndFlush(nova("evento-1", "sub-1", CRIADO_EM));
        processados.saveAndFlush(new EventoProcessado("evento-1", CanalNotificacao.INTERNA, CRIADO_EM));
        notificacoes.deleteById(notificacao.getId());
        assertThat(processados.existsByEventoIdAndCanal("evento-1", CanalNotificacao.INTERNA)).isTrue();
        assertThat(notificacoes.count()).isZero();
    }

    @Test
    void consultaPorDestinatarioOrdenaPaginaEContaSomenteNaoLidas() {
        var antiga = notificacoes.saveAndFlush(nova("evento-1", "sub-1", CRIADO_EM.minusSeconds(60)));
        var recente = notificacoes.saveAndFlush(nova("evento-2", "sub-1", CRIADO_EM));
        var empate = notificacoes.saveAndFlush(nova("evento-3", "sub-1", CRIADO_EM));
        notificacoes.saveAndFlush(nova("evento-4", "sub-2", CRIADO_EM));
        jdbc.update("update notificacao_interna set lida_em=criado_em where id=?", antiga.getId());
        var pagina = notificacoes.findByDestinatarioSubOrderByCriadoEmDescIdDesc("sub-1", PageRequest.of(0, 2));
        assertThat(pagina.getTotalElements()).isEqualTo(3);
        // Compara UUIDs como strings: o PostgreSQL ordena UUID pelos bytes sem sinal.
        var ids = List.of(recente.getId().toString(), empate.getId().toString()).stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(pagina.getContent()).extracting(n -> n.getId().toString()).containsExactlyElementsOf(ids);
        assertThat(notificacoes.findByDestinatarioSubOrderByCriadoEmDescIdDesc("sub-1", PageRequest.of(1, 2)).getContent())
                .extracting(NotificacaoInterna::getId).containsExactly(antiga.getId());
        assertThat(notificacoes.countByDestinatarioSubAndLidaEmIsNull("sub-1")).isEqualTo(2);
        assertThat(notificacoes.countByDestinatarioSubAndLidaEmIsNull("sub-2")).isEqualTo(1);
    }

    @Test
    void restricoesSqlRejeitamNulosCamposVaziosTipoDeEmailELeituraAnteriorACriacao() {
        String insert = "insert into notificacao_interna (id, destinatario_sub, evento_id, tipo, chamado_id, numero_chamado, titulo, criado_em, lida_em) "
                + "values (?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)";
        for (int index = 1; index <= 6; index++) {
            for (String invalid : new String[] {null, " "}) {
                Object[] args = {UUID.randomUUID(), "sub-1", "evento", "ATRIBUICAO", "1042", "#1042", "Titulo", null};
                args[index] = invalid;
                assertThatThrownBy(() -> jdbc.update(insert, args)).isInstanceOf(DataIntegrityViolationException.class);
            }
        }
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "sub-1", "evento", "RESOLUCAO", "1042", "#1042", "Titulo", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        var valida = notificacoes.saveAndFlush(nova("evento-valido", "sub-1", CRIADO_EM));
        assertThatThrownBy(() -> jdbc.update("update notificacao_interna set lida_em=criado_em - interval '1 second' where id=?", valida.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into evento_processado values (?, ?, ?, current_timestamp)", UUID.randomUUID(), "evento", "SMS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reverteNotificacoesEMarcadoresNaMesmaTransacao() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            notificacoes.saveAndFlush(nova("evento-1", "sub-1", CRIADO_EM));
            processados.saveAndFlush(new EventoProcessado("evento-1", CanalNotificacao.INTERNA, CRIADO_EM));
            throw new IllegalStateException("Falha simulada antes do commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notificacoes.count()).isZero();
        assertThat(processados.count()).isZero();
    }

    @Test
    void bancoNaoTemColunasDeEmailOuTextoDePosicionamento() {
        var colunas = jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name in ('notificacao_interna','evento_processado')", String.class, SCHEMA);
        assertThat(colunas).doesNotContain("email", "destinatarios", "texto_posicionamento", "payload");
    }

    private NotificacaoInterna nova(String evento, String sub, Instant criadoEm) {
        return new NotificacaoInterna(sub, evento, TipoEvento.ATRIBUICAO, "1042", "#1042", "Manutenção", criadoEm);
    }
}
