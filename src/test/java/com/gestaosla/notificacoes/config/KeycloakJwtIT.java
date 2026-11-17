package com.gestaosla.notificacoes.config;

import static com.gestaosla.notificacoes.suporte.ConfiguracaoIT.valor;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(ApiSegurancaTeste.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=${IT_KEYCLOAK_URL}/realms/flowsla-ms-not-test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${IT_KEYCLOAK_URL}/realms/flowsla-ms-not-test/protocol/openid-connect/certs",
        "notificacoes.seguranca.audience=ms-notificacoes", "notificacoes.seguranca.cors-origins=http://localhost:5173"
})
@Timeout(30)
class KeycloakJwtIT {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filtros;
    @Autowired JwtDecoder decoder;
    private MockMvc http;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void preparar() { http = MockMvcBuilders.webAppContextSetup(context).addFilters(filtros).build(); }

    @ParameterizedTest @CsvSource({"tecnico,TECNICO,200", "gestor,GESTOR,200", "cliente,CLIENTE,403"})
    void validaJwtRealEAutorizaPeloPapelDoRealm(String usuario, String papel, int status) throws Exception {
        String token = token(usuario, "ms-not-test");
        var jwt = decoder.decode(token);
        assertThat(jwt.getAudience()).contains("ms-notificacoes");
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaimAsMap("realm_access").get("roles").toString()).contains(papel);
        var result = http.perform(get("/notificacoes").param("sub", "outro-usuario")
                .header("Authorization", "Bearer " + token)).andExpect(status().is(status));
        if (status == 200) result.andExpect(content().string(jwt.getSubject()));
        http.perform(patch("/notificacoes/id/lida").header("Authorization", "Bearer " + token))
                .andExpect(status().is(status == 200 ? 204 : 403));
    }

    @Test void tokenDoMesmoRealmDestinadoSomenteAoBackendRecebe401() throws Exception {
        String token = token("gestor", "audiencia-incorreta");
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void alterarSubDeTokenRealInvalidaAssinatura() throws Exception {
        var original = SignedJWT.parse(token("tecnico", "ms-not-test"));
        var claims = new JWTClaimsSet.Builder(original.getJWTClaimsSet()).subject("outro-usuario").build();
        String adulterado = original.getHeader().toBase64URL() + "." + Base64URL.encode(claims.toString()) + "." + original.getSignature();
        http.perform(get("/notificacoes").header("Authorization", "Bearer " + adulterado)).andExpect(status().isUnauthorized());
    }

    @Test void assinaturaValidaNaoDispensaValidacaoDoEmissor() throws Exception {
        var incorreto = new SecurityConfig().jwtDecoder("https://outro.example/realms/flowsla", valor("IT_KEYCLOAK_URL")
                + "/realms/flowsla-ms-not-test/protocol/openid-connect/certs", "ms-notificacoes");
        String token = token("gestor", "ms-not-test");
        assertThatThrownBy(() -> incorreto.decode(token)).isInstanceOf(JwtException.class);
    }

    private String token(String usuario, String clientId) throws Exception {
        String form = "grant_type=password&client_id=" + clientId + "&username=" + usuario + "&password="
                + URLEncoder.encode(valor("IT_KEYCLOAK_PASSWORD"), StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(valor("IT_KEYCLOAK_URL")
                        + "/realms/flowsla-ms-not-test/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var token = JsonMapper.builder().build().readTree(response.body()).get("access_token");
        assertThat(token).isNotNull();
        return token.asText();
    }
}
