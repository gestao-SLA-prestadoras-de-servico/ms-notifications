package com.gestaosla.notificacoes.eventos;

import org.springframework.stereotype.Component;

import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Ponto de entrada para desserializar e validar o corpo JSON recebido do broker. */
@Component
public class LeitorEventoNotificacao {
    private final Validator validator;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    public LeitorEventoNotificacao(Validator validator) {
        this.validator = validator;
    }

    public EventoNotificacao ler(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new EventoInvalidoException();
        }
        EventoNotificacao evento;
        try {
            evento = mapper.readValue(payload, EventoNotificacao.class);
        } catch (JacksonException ex) {
            // A exceção de desserialização pode conter dados pessoais do payload.
            throw new EventoInvalidoException();
        }
        if (evento == null || !validator.validate(evento).isEmpty()) {
            throw new EventoInvalidoException();
        }
        if (evento.tipo().canal() == CanalNotificacao.EMAIL
                && !validator.validate(evento, EventoNotificacao.EmailCanal.class).isEmpty()) {
            throw new EventoInvalidoException();
        }
        return evento;
    }

    /** Também impede que um evento seja processado no canal incorreto. */
    public EventoNotificacao ler(String payload, CanalNotificacao canal) {
        EventoNotificacao evento = ler(payload);
        if (canal == null || evento.tipo().canal() != canal) {
            throw new EventoInvalidoException();
        }
        return evento;
    }
}
