package com.gestaosla.notificacoes.integracoes;

import jakarta.mail.MessagingException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class SondaSmtp {
    private final JavaMailSenderImpl sender;

    public SondaSmtp(JavaMailSenderImpl sender) { this.sender = sender; }

    public void verificar() throws MessagingException {
        // Mesmos host, credenciais, STARTTLS e timeouts do envio; não envia e-mail de teste.
        sender.testConnection();
    }
}
