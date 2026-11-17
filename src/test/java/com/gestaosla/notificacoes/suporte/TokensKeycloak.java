package com.gestaosla.notificacoes.suporte;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

public final class TokensKeycloak {
    private TokensKeycloak() { }

    public static String obter(String usuario) throws Exception {
        String form = "grant_type=password&client_id=ms-not-test&username=" + usuario + "&password="
                + URLEncoder.encode(ConfiguracaoIT.valor("IT_KEYCLOAK_PASSWORD"), StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(ConfiguracaoIT.valor("IT_KEYCLOAK_URL")
                        + "/realms/flowsla-ms-not-test/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Falha ao obter token: HTTP " + response.statusCode());
        return JsonMapper.builder().build().readTree(response.body()).get("access_token").asText();
    }
}
