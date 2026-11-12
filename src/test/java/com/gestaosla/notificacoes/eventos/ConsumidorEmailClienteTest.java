package com.gestaosla.notificacoes.eventos;

import com.gestaosla.notificacoes.integracoes.EnviadorEmailCliente;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.mail.MailSendException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsumidorEmailClienteTest {
    @Test
    void delegaAoAdaptadorDoCanalEmailSemAckAntecipado() throws Exception {
        var processor = mock(ProcessadorMensagemNotificacao.class);
        var sender = mock(EnviadorEmailCliente.class);
        var channel = mock(Channel.class);
        var message = mensagem();
        new ConsumidorEmailCliente(processor, sender).consumir(message, channel);
        verify(processor).processar(eq(message), eq(channel), eq(CanalNotificacao.EMAIL), any());
        verifyNoInteractions(channel);
    }

    @Test
    void invalidaERejeitadaSemRequeue() throws Exception {
        var processor = mock(ProcessadorMensagemNotificacao.class);
        when(processor.processar(any(), any(), any(), any())).thenThrow(new EventoInvalidoException());
        var channel = mock(Channel.class);
        new ConsumidorEmailCliente(processor, mock(EnviadorEmailCliente.class)).consumir(mensagem(), channel);
        verify(channel).basicReject(42L, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void falhaSmtpSolicitaReentregaSemAck() throws Exception {
        var processor = mock(ProcessadorMensagemNotificacao.class);
        when(processor.processar(any(), any(), any(), any())).thenThrow(new MailSendException("Falha simulada"));
        var channel = mock(Channel.class);
        new ConsumidorEmailCliente(processor, mock(EnviadorEmailCliente.class)).consumir(mensagem(), channel);
        verify(channel).basicNack(42L, false, true);
        verifyNoMoreInteractions(channel);
    }

    private Message mensagem() {
        var properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(new byte[0], properties);
    }
}
