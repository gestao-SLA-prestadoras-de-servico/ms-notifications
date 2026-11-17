package com.gestaosla.notificacoes.eventos;

import java.util.ArrayList;
import java.util.Map;
import org.springframework.amqp.core.*;

final class TopologiaRetryTeste {
    static Declarables criar(String exchangeTrabalho, int intervaloMs) {
        var recursos = new ArrayList<Declarable>();
        var exchange = new DirectExchange(exchangeTrabalho + ".retry", true, false);
        recursos.add(exchange);
        for (String canal : new String[] {"email", "interna"}) {
            for (int etapa = 1; etapa <= 3; etapa++) {
                var fila = new Queue(exchange.getName() + "." + canal + "." + etapa, true, false, false,
                        Map.of("x-queue-type", "quorum", "x-overflow", "reject-publish",
                                "x-message-ttl", intervaloMs * (1 << (etapa - 1)),
                                "x-dead-letter-exchange", exchangeTrabalho, "x-dead-letter-routing-key", canal));
                recursos.add(fila);
                recursos.add(BindingBuilder.bind(fila).to(exchange).with(canal + "." + etapa));
            }
        }
        return new Declarables(recursos);
    }
}
