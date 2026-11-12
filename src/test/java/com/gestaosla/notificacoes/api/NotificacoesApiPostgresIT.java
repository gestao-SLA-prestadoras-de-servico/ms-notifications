package com.gestaosla.notificacoes.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.gestaosla.notificacoes.eventos.TipoEvento;
import com.gestaosla.notificacoes.persistencia.NotificacaoInterna;
import com.gestaosla.notificacoes.persistencia.NotificacaoInternaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "notificacoes.consumidor-email.enabled=false", "notificacoes.consumidor-interno.enabled=false",
        "spring.datasource.url=${IT_DB_URL}", "spring.datasource.username=${IT_DB_USERNAME}",
        "spring.datasource.password=${IT_DB_PASSWORD}", "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"
})
@Timeout(30)
class NotificacoesApiPostgresIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant CRIACAO = Instant.parse("2026-11-11T13:00:00Z");
    private static final Instant LEITURA = CRIACAO.plusSeconds(60);
    private static final String SUB = "sub-opaco-tecnico";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @DynamicPropertySource static void schema(DynamicPropertyRegistry r) {
        r.add("spring.flyway.default-schema", () -> SCHEMA);
        r.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filtros;
    @Autowired NotificacaoInternaRepository repository;
    @Autowired NotificacoesService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    private MockMvc http;

    @BeforeEach void preparar() {
        repository.deleteAll();
        when(clock.instant()).thenReturn(LEITURA);
        when(decoder.decode("tecnico")).thenReturn(jwt(SUB, "TECNICO"));
        when(decoder.decode("gestor")).thenReturn(jwt("sub-gestor", "GESTOR"));
        when(decoder.decode("cliente")).thenReturn(jwt("sub-cliente", "CLIENTE"));
        http = MockMvcBuilders.webAppContextSetup(context).addFilters(filtros).build();
    }

    @Test void paginaSomentePropriasNotificacoesEContaNaoLidasForaDaPagina() throws Exception {
        var antiga = nova(SUB, CRIACAO);
        var recente = nova(SUB, CRIACAO.plusSeconds(1));
        nova("outro", CRIACAO.plusSeconds(10));
        var pagina = consultar("/notificacoes?page=0&size=1&sub=outro&destinatarioSub=outro&sort=criadoEm,asc", "tecnico");
        assertThat(pagina.get("itens").size()).isEqualTo(1);
        assertThat(pagina.at("/itens/0/id").asText()).isEqualTo(recente.getId().toString());
        assertThat(pagina.get("totalElementos").asLong()).isEqualTo(2);
        assertThat(pagina.get("totalPaginas").asInt()).isEqualTo(2);
        assertThat(pagina.get("totalNaoLidas").asLong()).isEqualTo(2);
        assertThat(pagina.get("pagina").asInt()).isZero();
        assertThat(pagina.get("tamanho").asInt()).isEqualTo(1);
        var segunda = consultar("/notificacoes?page=1&size=1", "tecnico");
        assertThat(segunda.at("/itens/0/id").asText()).isEqualTo(antiga.getId().toString());
        assertThat(segunda.get("totalNaoLidas").asLong()).isEqualTo(2);
    }

    @Test void empatesUsamIdDecrescenteSemDuplicarItensEntrePaginas() throws Exception {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 4; i++) ids.add(nova(SUB, CRIACAO).getId().toString());
        ids.sort(java.util.Comparator.reverseOrder());
        for (int i = 0; i < ids.size(); i++) {
            assertThat(consultar("/notificacoes?page=" + i + "&size=1", "tecnico").at("/itens/0/id").asText()).isEqualTo(ids.get(i));
        }
    }

    @Test void paginaVaziaEForaDoIntervaloPreservamMetadadosEContagemGlobal() throws Exception {
        var vazia = consultar("/notificacoes", "tecnico");
        assertThat(vazia.get("itens").size()).isZero();
        assertThat(vazia.get("tamanho").asInt()).isEqualTo(20);
        assertThat(vazia.get("totalElementos").asLong()).isZero();
        assertThat(vazia.get("totalNaoLidas").asLong()).isZero();
        nova(SUB, CRIACAO);
        var fora = consultar("/notificacoes?page=10", "tecnico");
        assertThat(fora.get("itens").size()).isZero();
        assertThat(fora.get("totalElementos").asLong()).isEqualTo(1);
        assertThat(fora.get("totalNaoLidas").asLong()).isEqualTo(1);
    }

    @Test void gestorTambemVeSomenteSeuSub() throws Exception {
        nova(SUB, CRIACAO);
        var gestor = nova("sub-gestor", CRIACAO);
        var pagina = consultar("/notificacoes", "gestor");
        assertThat(pagina.get("totalElementos").asLong()).isEqualTo(1);
        assertThat(pagina.at("/itens/0/id").asText()).isEqualTo(gestor.getId().toString());
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=x", "size=x", "page=2147483648", "page=2147483647&size=100"})
    void paginacaoInvalidaRetorna400SemDetalhesInternos(String query) throws Exception {
        http.perform(get("/notificacoes?" + query).header("Authorization", "Bearer tecnico"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test void tamanhoMaximoPermitidoFunciona() throws Exception {
        assertThat(consultar("/notificacoes?size=100", "tecnico").get("tamanho").asInt()).isEqualTo(100);
    }

    @Test void leituraRetorna204PreservaPrimeiroInstanteEAtualizaContagem() throws Exception {
        var n = nova(SUB, CRIACAO);
        nova(SUB, CRIACAO.minusSeconds(1));
        marcar(n.getId(), "tecnico", 204);
        assertThat(repository.findById(n.getId()).orElseThrow().getLidaEm()).isEqualTo(LEITURA);
        when(clock.instant()).thenReturn(LEITURA.plusSeconds(500));
        marcar(n.getId(), "tecnico", 204);
        assertThat(repository.findById(n.getId()).orElseThrow().getLidaEm()).isEqualTo(LEITURA);
        var pagina = consultar("/notificacoes", "tecnico");
        assertThat(pagina.get("totalElementos").asLong()).isEqualTo(2);
        assertThat(pagina.get("totalNaoLidas").asLong()).isEqualTo(1);
        assertThat(pagina.at("/itens/0/lidaEm").asText()).isEqualTo(LEITURA.toString());
    }

    @Test void outroUsuarioEIdInexistenteRetornamMesmo404SemAlterarDados() throws Exception {
        var n = nova("outro", CRIACAO);
        var outro = erroLeitura(n.getId());
        var inexistente = erroLeitura(UUID.randomUUID());
        assertThat(outro.get("detail").asText()).isEqualTo(inexistente.get("detail").asText());
        assertThat(outro.get("title").asText()).isEqualTo(inexistente.get("title").asText());
        assertThat(repository.findById(n.getId()).orElseThrow().getLidaEm()).isNull();
    }

    @Test void uuidInvalidoRetorna400() throws Exception {
        http.perform(patch("/notificacoes/uuid-invalido/lida").header("Authorization", "Bearer tecnico"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test void autenticacaoEPapelSaoExigidosAntesDeValidarParametros() throws Exception {
        http.perform(get("/notificacoes?page=-1")).andExpect(status().isUnauthorized());
        http.perform(patch("/notificacoes/uuid-invalido/lida")).andExpect(status().isUnauthorized());
        http.perform(get("/notificacoes?page=-1").header("Authorization", "Bearer cliente")).andExpect(status().isForbidden());
        http.perform(patch("/notificacoes/uuid-invalido/lida").header("Authorization", "Bearer cliente")).andExpect(status().isForbidden());
    }

    @Test void conteudoPublicoConfereComOpenApiSemExporDestinatarioOuEvento() throws Exception {
        nova(SUB, CRIACAO);
        var pagina = consultar("/notificacoes", "tecnico");
        var contrato = JSON.readTree(Files.readString(Path.of("docs/openapi.json")));
        assertThat(pagina.propertyNames()).containsExactlyInAnyOrderElementsOf(
                contrato.at("/components/schemas/PaginaNotificacoes/properties").propertyNames());
        var item = pagina.at("/itens/0");
        assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(
                contrato.at("/components/schemas/Notificacao/properties").propertyNames());
        assertThat(item.get("chamadoId").asText()).isEqualTo("chamado-opaco");
        assertThat(item.get("numeroChamado").asText()).isEqualTo("#1042");
        assertThat(item.get("mensagem").asText()).contains("#1042", "Manutenção");
        assertThat(item.get("lidaEm").isNull()).isTrue();
        assertThat(item.has("destinatarioSub")).isFalse();
        assertThat(item.has("eventoId")).isFalse();
    }

    @Test void dataDeLeituraNuncaFicaAntesDaCriacaoMesmoComRelogioAtrasado() throws Exception {
        var n = nova(SUB, LEITURA.plusSeconds(300));
        marcar(n.getId(), "tecnico", 204);
        assertThat(repository.findById(n.getId()).orElseThrow().getLidaEm()).isEqualTo(n.getCriadoEm());
    }

    @Test void leiturasConcorrentesEsperamLockEPreservamDataDaPrimeiraTransacao() throws Exception {
        var n = nova(SUB, CRIACAO);
        when(clock.instant()).thenReturn(LEITURA, LEITURA.plusSeconds(100));
        var gravada = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        String application = SCHEMA + "_segunda";
        try (var workers = Executors.newFixedThreadPool(2)) {
            var primeira = workers.submit(() -> autenticado(() -> transaction.executeWithoutResult(tx -> {
                service.marcarComoLida(n.getId());
                gravada.countDown();
                try { if (!liberar.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout do teste"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            })));
            try {
                if (!gravada.await(5, TimeUnit.SECONDS)) {
                    primeira.get(1, TimeUnit.SECONDS);
                    fail("A primeira transação não alcançou a atualização");
                }
                var segunda = workers.submit(() -> autenticado(() -> transaction.executeWithoutResult(tx -> {
                    jdbc.execute("set local application_name='" + application + "'");
                    service.marcarComoLida(n.getId());
                })));
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock'",
                        Integer.class, application) > 0);
                liberar.countDown();
                primeira.get(5, TimeUnit.SECONDS); segunda.get(5, TimeUnit.SECONDS);
            } finally { liberar.countDown(); }
        }
        assertThat(repository.findById(n.getId()).orElseThrow().getLidaEm()).isEqualTo(LEITURA);
    }

    private JsonNode consultar(String path, String token) throws Exception {
        var result = http.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void marcar(UUID id, String token, int esperado) throws Exception {
        http.perform(patch("/notificacoes/" + id + "/lida").header("Authorization", "Bearer " + token))
                .andExpect(status().is(esperado)).andExpect(content().string(""));
    }
    private JsonNode erroLeitura(UUID id) throws Exception {
        var result = http.perform(patch("/notificacoes/" + id + "/lida").header("Authorization", "Bearer tecnico"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private NotificacaoInterna nova(String sub, Instant criado) {
        return repository.saveAndFlush(new NotificacaoInterna(sub, UUID.randomUUID().toString(), TipoEvento.ATRIBUICAO,
                "chamado-opaco", "#1042", "Manutenção", criado));
    }
    private Jwt jwt(String sub, String role) {
        return Jwt.withTokenValue(role).header("alg", "RS256").subject(sub).claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(CRIACAO).expiresAt(LEITURA.plusSeconds(300)).build();
    }
    private void autenticado(Runnable acao) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(SUB, "TECNICO"),
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TECNICO"))));
        try { acao.run(); } finally { SecurityContextHolder.clearContext(); }
    }
}
