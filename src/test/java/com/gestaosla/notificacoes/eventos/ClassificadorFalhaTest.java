package com.gestaosla.notificacoes.eventos;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Map;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.MailSendException;
import static org.assertj.core.api.Assertions.*;

class ClassificadorFalhaTest {
    private final ClassificadorFalha classifier = new ClassificadorFalha();

    @ParameterizedTest
    @CsvSource({"421,TEMPORARIA", "450,TEMPORARIA", "451,TEMPORARIA", "499,TEMPORARIA", "500,DEFINITIVA", "550,DEFINITIVA", "599,DEFINITIVA"})
    void classificaCodigoSmtpInclusiveNaListaDeFalhas(int code, ClassificadorFalha.Natureza expected) throws Exception {
        var smtp = new SMTPAddressFailedException(new InternetAddress("cliente@example.com"), "RCPT TO", code, "Resposta simulada");
        var exception = new MailSendException(Map.of(new Object(), smtp));
        assertThat(classifier.classificar(exception)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"08006,TEMPORARIA", "40001,TEMPORARIA", "40P01,TEMPORARIA", "55P03,TEMPORARIA", "57014,TEMPORARIA", "53300,TEMPORARIA", "23505,DEFINITIVA", "42P01,DEFINITIVA", "P0001,DEFINITIVA"})
    void classificaSqlStateSemInterpretarTexto(String state, ClassificadorFalha.Natureza expected) {
        assertThat(classifier.classificar(new RuntimeException(new SQLException("Mensagem privada", state)))).isEqualTo(expected);
    }

    @Test
    void percorreNextExceptionDoJavaMailParaEncontrarTimeout() {
        var nested = new MessagingException("Falha SMTP");
        nested.setNextException(new SocketTimeoutException());
        assertThat(classifier.classificar(new MailSendException("Falha", nested))).isEqualTo(ClassificadorFalha.Natureza.TEMPORARIA);
    }

    @Test
    void codigo5xxPrevaleceSobreOutroErroTemporario() throws Exception {
        var permanent = new SMTPAddressFailedException(new InternetAddress("cliente@example.com"), "RCPT TO", 550, "Falha");
        assertThat(classifier.classificar(new MailSendException(Map.of(new Object(), new SocketTimeoutException(), new Object(), permanent))))
                .isEqualTo(ClassificadorFalha.Natureza.DEFINITIVA);
    }

    @Test
    void invalidaEErroDesconhecidoSaoDefinitivos() {
        assertThat(classifier.classificar(new EventoInvalidoException())).isEqualTo(ClassificadorFalha.Natureza.DEFINITIVA);
        assertThat(classifier.classificar(new IllegalStateException())).isEqualTo(ClassificadorFalha.Natureza.DEFINITIVA);
    }

    @ParameterizedTest
    @CsvSource({"454,TEMPORARIA", "535,DEFINITIVA"})
    void classificaRespostaDeAutenticacao(int code, ClassificadorFalha.Natureza expected) {
        assertThat(classifier.classificar(new org.springframework.mail.MailAuthenticationException(new jakarta.mail.AuthenticationFailedException(code + " Resposta SMTP"))))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"421,TEMPORARIA", "554,DEFINITIVA"})
    void classificaRespostaDeSaudacao(int code, ClassificadorFalha.Natureza expected) {
        assertThat(classifier.classificar(new MessagingException("Could not connect to SMTP host: smtp, port: 25, response: " + code)))
                .isEqualTo(expected);
    }

    @Test
    void conexaoSmtpInterrompidaETemporaria() {
        assertThat(classifier.classificar(new MailSendException("Falha", new java.net.SocketException("Connection reset"))))
                .isEqualTo(ClassificadorFalha.Natureza.TEMPORARIA);
        var eof = new MailSendException("Falha", new java.io.EOFException());
        assertThat(classifier.classificar(eof)).isEqualTo(ClassificadorFalha.Natureza.TEMPORARIA);
        assertThat(classifier.indisponibilidadeSmtp(eof)).isTrue();
    }
}
