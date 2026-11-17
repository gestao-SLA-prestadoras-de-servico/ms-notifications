package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ponto de entrada para desserializar e validar o corpo JSON recebido do broker.
 *
 * <p>Valida com os JSON Schemas do app-principal (cópia de {@code backend/contratos/notificacoes/}), com
 * {@code format} conferido: no draft 2020-12 ele é só anotação por padrão, e "amanhã" passaria como data
 * (regra 8 de CONVENÇÕES_RABBITMQ.md).
 */
@Component
public class LeitorEventoNotificacao {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final Map<TipoEvento, Schema> schemas = new EnumMap<>(TipoEvento.class);

    public LeitorEventoNotificacao() {
        var registro = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, r -> r
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        for (TipoEvento tipo : TipoEvento.values()) {
            String caminho = "/contratos/notificacoes/" + tipo.arquivoSchema();
            try (InputStream arquivo = getClass().getResourceAsStream(caminho)) {
                if (arquivo == null) {
                    throw new IllegalStateException("Schema ausente: " + caminho);
                }
                schemas.put(tipo, registro.getSchema(mapper.readTree(arquivo)));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    public EventoNotificacao ler(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new EventoInvalidoException();
        }
        try {
            JsonNode json = mapper.readTree(payload);
            Schema schema = schemas.get(tipo(json));
            // Os erros de validação podem conter dados pessoais do payload; não são propagados.
            if (schema == null || !schema.validate(json).isEmpty()) {
                throw new EventoInvalidoException();
            }
            return mapper.treeToValue(json, EventoNotificacao.class);
        } catch (JacksonException ex) {
            throw new EventoInvalidoException();
        }
    }

    /** Também impede que um evento seja processado no canal incorreto. */
    public EventoNotificacao ler(String payload, CanalNotificacao canal) {
        EventoNotificacao evento = ler(payload);
        if (canal == null || evento.tipo().canal() != canal) {
            throw new EventoInvalidoException();
        }
        return evento;
    }

    private static TipoEvento tipo(JsonNode json) {
        JsonNode tipo = json.get("tipo");
        if (tipo == null || !tipo.isString()) {
            return null;
        }
        try {
            return TipoEvento.valueOf(tipo.stringValue());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
