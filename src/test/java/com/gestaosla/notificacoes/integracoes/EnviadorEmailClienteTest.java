package com.gestaosla.notificacoes.integracoes;

import java.util.List;
import java.util.Properties;

import com.gestaosla.notificacoes.eventos.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnviadorEmailClienteTest {
    @Test
    void enviaSeparadamenteCadaContatoSemDuplicarEnderecoRepetido() throws Exception {
        var sender = sender();
        var service = new EnviadorEmailCliente(sender, new TemplateEmailCliente(), "notificacoes@example.com");
        service.enviar(evento());
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(captor.capture());
        var messages = captor.getAllValues();
        assertThat(messages.getFirst().getAllRecipients()).extracting(Object::toString).containsExactly("cliente1@example.com");
        assertThat(messages.getLast().getAllRecipients()).extracting(Object::toString).containsExactly("cliente2@example.com");
        for (var message : messages) {
            assertThat(message.getFrom()).extracting(Object::toString).containsExactly("notificacoes@example.com");
            assertThat(message.getSubject()).contains("#1042", "Manutenção");
        }
    }

    @Test
    void propagaFalhaSmtpEInterrompeRestanteDoLote() {
        var sender = sender();
        doThrow(new MailSendException("Falha simulada")).when(sender).send(any(MimeMessage.class));
        var service = new EnviadorEmailCliente(sender, new TemplateEmailCliente(), "notificacoes@example.com");
        assertThatThrownBy(() -> service.enviar(evento())).isInstanceOf(MailSendException.class);
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    private JavaMailSender sender() {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        return sender;
    }

    private EventoNotificacao evento() {
        return new EventoNotificacao("evento", TipoEvento.RESOLUCAO, new ChamadoEvento("id", "#1042"), "Manutenção",
                List.of("cliente1@example.com", "cliente1@example.com", "cliente2@example.com"), null);
    }
}
