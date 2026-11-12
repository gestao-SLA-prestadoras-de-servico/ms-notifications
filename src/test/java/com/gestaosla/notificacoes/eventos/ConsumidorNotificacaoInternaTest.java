package com.gestaosla.notificacoes.eventos;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsumidorNotificacaoInternaTest {
    @Test
    void delegaAoAdaptadorQueConfirmaSomenteDepoisDoCommit() throws Exception {
        var processor = mock(ProcessadorMensagemNotificacao.class);
        var persistidor = mock(PersistidorNotificacaoInterna.class);
        var channel = mock(Channel.class);
        var message = mensagem();
        new ConsumidorNotificacaoInterna(processor, persistidor).consumir(message, channel);
        verify(processor).processar(eq(message), eq(channel), eq(CanalNotificacao.INTERNA), any());
        verifyNoInteractions(channel);
    }

    @Test
    void invalidaERejeitadaSemRequeueOuAck() throws Exception {
        var processor = mock(ProcessadorMensagemNotificacao.class);
        when(processor.processar(any(), any(), any(), any())).thenThrow(new EventoInvalidoException());
        var channel = mock(Channel.class);
        new ConsumidorNotificacaoInterna(processor, mock(PersistidorNotificacaoInterna.class)).consumir(mensagem(), channel);
        verify(channel).basicReject(42L, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void falhaDeProcessamentoSolicitaReentregaSemAck() throws Exception {
        var processor = mock(ProcessadorMensagemNotificacao.class);
        when(processor.processar(any(), any(), any(), any())).thenThrow(new IllegalStateException("Falha simulada"));
        var channel = mock(Channel.class);
        new ConsumidorNotificacaoInterna(processor, mock(PersistidorNotificacaoInterna.class)).consumir(mensagem(), channel);
        verify(channel).basicNack(42L, false, true);
        verifyNoMoreInteractions(channel);
    }

    private Message mensagem() {
        var properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(new byte[0], properties);
    }
}
