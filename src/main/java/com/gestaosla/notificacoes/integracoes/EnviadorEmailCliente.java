package com.gestaosla.notificacoes.integracoes;

import java.nio.charset.StandardCharsets;

import com.gestaosla.notificacoes.eventos.EventoNotificacao;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnviadorEmailCliente {
    private final JavaMailSender sender;
    private final TemplateEmailCliente template;
    private final String remetente;
    private final ProtecaoSmtp protecao;

    public EnviadorEmailCliente(JavaMailSender sender, TemplateEmailCliente template,
            @Value("${notificacoes.email.remetente}") String remetente, ProtecaoSmtp protecao) {
        this.sender = sender;
        this.template = template;
        this.remetente = remetente;
        this.protecao = protecao;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enviar(EventoNotificacao evento) {
        var conteudo = template.compor(evento);
        for (String destinatario : evento.destinatarios().stream().distinct().toList()) {
            var mensagem = sender.createMimeMessage();
            try {
                var helper = new MimeMessageHelper(mensagem, true, StandardCharsets.UTF_8.name());
                helper.setValidateAddresses(true);
                helper.setFrom(remetente);
                helper.setTo(destinatario);
                helper.setSubject(conteudo.assunto());
                helper.setText(conteudo.texto(), conteudo.html());
            } catch (MessagingException ex) {
                throw new MailPreparationException("Não foi possível preparar o e-mail ao cliente");
            }
            // Um envelope por endereço, sem expor outros contatos do evento no cabeçalho To.
            protecao.executar(() -> sender.send(mensagem));
        }
    }
}
