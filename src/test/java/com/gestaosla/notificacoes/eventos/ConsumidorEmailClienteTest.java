package com.gestaosla.notificacoes.eventos;

import com.gestaosla.notificacoes.integracoes.EnviadorEmailCliente;
import java.io.IOException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsumidorEmailClienteTest {
    @Test
    void delegaAoFluxoComRetryNoCanalCorreto() throws Exception {
        var processor = mock(ProcessadorEntrega.class);
        var service = mock(EnviadorEmailCliente.class);
        var channel = mock(Channel.class);
        var message = mensagem();
        new ConsumidorEmailCliente(processor, service).consumir(message, channel);
        verify(processor).consumir(eq(message), eq(channel), eq(CanalNotificacao.EMAIL), any());
        verifyNoInteractions(channel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void entregaAcaoConcretaAoProcessador() throws Exception {
        var processor = mock(ProcessadorEntrega.class);
        var service = mock(EnviadorEmailCliente.class);
        var event = mock(EventoNotificacao.class);
        doAnswer(inv -> { ((java.util.function.Consumer<EventoNotificacao>) inv.getArgument(3)).accept(event); return null; })
                .when(processor).consumir(any(), any(), any(), any());
        new ConsumidorEmailCliente(processor, service).consumir(mensagem(), mock(Channel.class));
        verify(service).enviar(event);
    }

    @Test
    void propagaFalhaDeTransporteSemConfirmarPorContaPropria() throws Exception {
        var processor = mock(ProcessadorEntrega.class);
        doThrow(new IOException("Falha simulada")).when(processor).consumir(any(), any(), any(), any());
        var channel = mock(Channel.class);
        assertThatThrownBy(() -> new ConsumidorEmailCliente(processor, mock(EnviadorEmailCliente.class)).consumir(mensagem(), channel)).isInstanceOf(IOException.class);
        verifyNoInteractions(channel);
    }

    private Message mensagem() {
        var properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(new byte[0], properties);
    }
}
