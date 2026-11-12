package com.gestaosla.notificacoes.eventos;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

@Component
public class ClassificadorFalha {
    public boolean indisponibilidadeSmtp(Throwable falha) {
        var pendentes = new ArrayDeque<Throwable>();
        var vistos = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pendentes.add(falha);
        boolean indisponivel = false;
        while (!pendentes.isEmpty()) {
            var atual = pendentes.remove();
            if (!vistos.add(atual)) continue;
            int codigo = codigoSmtp(atual);
            if (codigo >= 500 && codigo <= 599) return false;
            indisponivel |= codigo == 421 || atual instanceof SocketException || atual instanceof SocketTimeoutException
                    || atual instanceof UnknownHostException || atual instanceof java.io.EOFException;
            if (atual.getCause() != null) pendentes.add(atual.getCause());
            if (atual instanceof MessagingException mail && mail.getNextException() != null) pendentes.add(mail.getNextException());
            if (atual instanceof MailSendException mail) Collections.addAll(pendentes, mail.getMessageExceptions());
        }
        return indisponivel;
    }
    // Alguns erros de saudação/autenticação do provider só expõem a resposta SMTP em texto.
    private static final Pattern RESPOSTA_SMTP = Pattern.compile("(?s)^(?:([45][0-9]{2})(?:\\s|-|$)|.*\\bresponse:\\s*([45][0-9]{2})(?:\\D|$))");
    public enum Natureza { TEMPORARIA, DEFINITIVA }

    public Natureza classificar(Throwable falha) {
        var pendentes = new ArrayDeque<Throwable>();
        var vistos = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pendentes.add(falha);
        boolean temporaria = false;
        while (!pendentes.isEmpty()) {
            var atual = pendentes.remove();
            if (!vistos.add(atual)) continue;
            if (atual instanceof EventoInvalidoException) return Natureza.DEFINITIVA;
            int smtp = codigoSmtp(atual);
            if (smtp >= 500 && smtp <= 599) return Natureza.DEFINITIVA;
            temporaria |= smtp >= 400 && smtp <= 499;
            temporaria |= atual instanceof SocketTimeoutException || atual instanceof SocketException || atual instanceof UnknownHostException
                    || atual instanceof java.io.EOFException
                    || atual instanceof TransientDataAccessException || atual instanceof RecoverableDataAccessException
                    || atual instanceof SQLTransientException || atual instanceof SQLRecoverableException;
            if (atual instanceof SQLException sql) {
                String state = sql.getSQLState();
                temporaria |= state != null && (state.startsWith("08") || state.startsWith("40")
                        || state.startsWith("53") || state.equals("55P03") || state.equals("57014")
                        || state.equals("57P01") || state.equals("57P02") || state.equals("57P03"));
                if (sql.getNextException() != null) pendentes.add(sql.getNextException());
            }
            if (atual.getCause() != null) pendentes.add(atual.getCause());
            if (atual instanceof MessagingException mail && mail.getNextException() != null) pendentes.add(mail.getNextException());
            if (atual instanceof MailSendException mail) Collections.addAll(pendentes, mail.getMessageExceptions());
        }
        return temporaria ? Natureza.TEMPORARIA : Natureza.DEFINITIVA;
    }

    private int codigoSmtp(Throwable falha) {
        if (falha instanceof SMTPAddressFailedException smtp) return smtp.getReturnCode();
        if (falha instanceof SMTPSendFailedException smtp) return smtp.getReturnCode();
        if (falha instanceof SMTPSenderFailedException smtp) return smtp.getReturnCode();
        if (falha instanceof MessagingException && falha.getMessage() != null) {
            var matcher = RESPOSTA_SMTP.matcher(falha.getMessage());
            if (matcher.find()) return Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return 0;
    }
}
