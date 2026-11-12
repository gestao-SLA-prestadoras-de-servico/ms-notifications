package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsumidorNotificacaoInternaTest {
    @Test
    void delegaAoFluxoComRetryNoCanalCorreto() throws Exception {
        var processor = mock(ProcessadorEntrega.class);
        var service = mock(PersistidorNotificacaoInterna.class);
        var channel = mock(Channel.class);
        var message = mensagem();
        new ConsumidorNotificacaoInterna(processor, service).consumir(message, channel);
        verify(processor).consumir(eq(message), eq(channel), eq(CanalNotificacao.INTERNA), any());
        verifyNoInteractions(channel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void entregaAcaoConcretaAoProcessador() throws Exception {
        var processor = mock(ProcessadorEntrega.class);
        var service = mock(PersistidorNotificacaoInterna.class);
        var event = mock(EventoNotificacao.class);
        doAnswer(inv -> { ((java.util.function.Consumer<EventoNotificacao>) inv.getArgument(3)).accept(event); return null; })
                .when(processor).consumir(any(), any(), any(), any());
        new ConsumidorNotificacaoInterna(processor, service).consumir(mensagem(), mock(Channel.class));
        verify(service).persistir(event);
    }

    @Test
    void propagaFalhaDeTransporteSemConfirmarPorContaPropria() throws Exception {
        var processor = mock(ProcessadorEntrega.class);
        doThrow(new IOException("Falha simulada")).when(processor).consumir(any(), any(), any(), any());
        var channel = mock(Channel.class);
        assertThatThrownBy(() -> new ConsumidorNotificacaoInterna(processor, mock(PersistidorNotificacaoInterna.class)).consumir(mensagem(), channel)).isInstanceOf(IOException.class);
        verifyNoInteractions(channel);
    }

    private Message mensagem() {
        var properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(new byte[0], properties);
    }
}
