package com.gestaosla.notificacoes.config;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(ApiSegurancaTeste.class)
@TestPropertySource(properties = {
        "notificacoes.seguranca.audience=ms-notificacoes",
        "notificacoes.seguranca.cors-origins=http://localhost:5173,https://frontend.example"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SegurancaJwtTest {
    private static final String ISSUER = "https://keycloak.example/realms/flowsla";
    private static final String SUB = "identidade-opaca-sem-normalizacao";
    private static final RSAKey CHAVE = chave();
    private static final HttpServer JWKS = servidor();
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filtros;
    private MockMvc http;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
    }

    @BeforeEach void preparar() { http = MockMvcBuilders.webAppContextSetup(context).addFilters(filtros).build(); }
    @AfterAll static void encerrar() { JWKS.stop(0); }

    @ParameterizedTest @ValueSource(strings = {"TECNICO", "GESTOR"})
    void permitePapeisDoRealmEPreservaSubIgnorandoIdentidadeDaRequisicao(String role) throws Exception {
        String token = token(b -> b.claim("realm_access", Map.of("roles", List.of(role))), CHAVE);
        http.perform(get("/notificacoes").param("sub", "outro").header("X-User-Id", "outro")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(SUB))
                .andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(patch("/notificacoes/qualquer/lida").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest @ValueSource(strings = {"CLIENTE", "ADMIN", "tecnico", "ROLE_TECNICO", "offline_access", ""})
    void perfilNaoAutorizadoRecebe403(String role) throws Exception {
        String token = token(b -> b.claim("realm_access", Map.of("roles", List.of(role))), CHAVE);
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        http.perform(patch("/notificacoes/id/lida").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test void papeisDeClientScopeOuClaimsAlternativosNaoConcedemAcesso() throws Exception {
        String token = token(b -> b.claim("resource_access", Map.of("client", Map.of("roles", List.of("GESTOR"))))
                .claim("scope", "GESTOR TECNICO").claim("perfil", "GESTOR").claim("roles", List.of("TECNICO")), CHAVE);
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"ausente", "texto", "lista", "rolesTexto"})
    void claimDePapeisAusenteOuMalformadoRecebe403(String caso) throws Exception {
        String token = token(b -> {
            if (caso.equals("texto")) b.claim("realm_access", "GESTOR");
            if (caso.equals("lista")) b.claim("realm_access", List.of("GESTOR"));
            if (caso.equals("rolesTexto")) b.claim("realm_access", Map.of("roles", "GESTOR"));
        }, CHAVE);
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "semAudience", "expirado", "futuro", "semExp", "semSub", "subVazio", "subBranco"})
    void rejeitaClaimsInvalidosCom401(String caso) throws Exception {
        String token = token(b -> {
            b.claim("realm_access", Map.of("roles", List.of("GESTOR")));
            switch (caso) {
                case "issuer" -> b.issuer("https://outro.example/realms/flowsla");
                case "audience" -> b.audience("app-principal");
                case "semAudience" -> b.audience((String) null);
                case "expirado" -> b.expirationTime(Date.from(Instant.now().minusSeconds(120)));
                case "futuro" -> b.notBeforeTime(Date.from(Instant.now().plusSeconds(120)));
                case "semExp" -> b.expirationTime(null);
                case "semSub" -> b.subject(null);
                case "subVazio" -> b.subject("");
                case "subBranco" -> b.subject("  ");
            }
        }, CHAVE);
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
    }

    @Test void assinaturaDeOutraChaveERecusada() throws Exception {
        String token = token(b -> b.claim("realm_access", Map.of("roles", List.of("GESTOR"))), chave());
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void naoAceitaAlgoritmoHmacMesmoComClaimsValidos() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUB).audience("ms-notificacoes")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of("GESTOR"))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(new java.security.SecureRandom().generateSeed(64)));
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + jwt.serialize())).andExpect(status().isUnauthorized());
    }

    @Test void tokenEmParametroOuCookieNaoSubstituiHeaderBearer() throws Exception {
        String token = token(b -> b.claim("realm_access", Map.of("roles", List.of("GESTOR"))), CHAVE);
        http.perform(get("/notificacoes").param("access_token", token)).andExpect(status().isUnauthorized());
        http.perform(get("/notificacoes").cookie(new jakarta.servlet.http.Cookie("access_token", token)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"", "Bearer invalido", "Basic dGVzdGU6dGVzdGU=", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."})
    void semBearerValidoRecebe401InclusiveNoPatch(String authorization) throws Exception {
        http.perform(get("/notificacoes").header("Authorization", authorization)).andExpect(status().isUnauthorized());
        http.perform(patch("/notificacoes/id/lida").header("Authorization", authorization)).andExpect(status().isUnauthorized());
    }

    @Test void sessaoNaoSubstituiJwtENaoAutorizaRequisicaoSeguinte() throws Exception {
        var session = new MockHttpSession();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(SUB, "", List.of(new SimpleGrantedAuthority("ROLE_GESTOR")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        http.perform(get("/notificacoes").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void somenteHealthEPublicoERotasForaDoEscopoContinuamNegadas() throws Exception {
        http.perform(get("/actuator/health")).andExpect(status().isOk());
        String token = token(b -> b.claim("realm_access", Map.of("roles", List.of("GESTOR"))), CHAVE);
        for (String path : List.of("/fora", "/actuator/info", "/api/notificacoes", "/login")) {
            http.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
    }

    @Test void despachoDeErroInternoPreserva404SemPublicarRotaDeErro() throws Exception {
        http.perform(get("/error")).andExpect(status().isUnauthorized());
        http.perform(get("/error").with(request -> {
            request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
            return request;
        })).andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings = {"http://localhost:5173", "https://frontend.example"})
    void corsPermitePreflightSemTokenNasOrigensConfiguradas(String origin) throws Exception {
        http.perform(options("/notificacoes/id/lida").header("Origin", origin)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        http.perform(get("/notificacoes").header("Origin", origin)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @ParameterizedTest @ValueSource(strings = {"https://evil.example", "https://frontend.example.evil", "null"})
    void corsRejeitaOrigemNaoConfigurada(String origin) throws Exception {
        http.perform(options("/notificacoes").header("Origin", origin).header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void corsRejeitaMetodoEHeaderNaoPermitidos() throws Exception {
        http.perform(options("/notificacoes").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
        http.perform(options("/notificacoes").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-User-Id")).andExpect(status().isForbidden());
    }

    private static String token(Consumer<JWTClaimsSet.Builder> alterar, RSAKey chave) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUB).audience("ms-notificacoes")
                .issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now.plusSeconds(300)));
        alterar.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("chave-teste").type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(chave));
        return jwt.serialize();
    }

    private static RSAKey chave() {
        try { return new RSAKeyGenerator(2048).keyID("chave-teste").generate(); }
        catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    private static HttpServer servidor() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/certs", exchange -> {
                byte[] body = new JWKSet(CHAVE.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start(); return server;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
}
