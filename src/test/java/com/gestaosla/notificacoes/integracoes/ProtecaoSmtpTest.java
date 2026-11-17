package com.gestaosla.notificacoes.integracoes;

import java.net.ConnectException;
import java.time.Duration;
import com.gestaosla.notificacoes.config.SmtpProperties;
import com.gestaosla.notificacoes.eventos.ClassificadorFalha;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class ProtecaoSmtpTest {
    private final ClassificadorFalha classificador = new ClassificadorFalha();
    private final CircuitBreaker circuito = CircuitBreaker.of("teste", CircuitBreakerConfig.custom()
            .slidingWindowSize(1).minimumNumberOfCalls(1).permittedNumberOfCallsInHalfOpenState(1)
            .waitDurationInOpenState(Duration.ofMillis(1))
            .ignoreException(falha -> !classificador.indisponibilidadeSmtp(falha)).build());
    private final SondaSmtp sonda = mock(SondaSmtp.class);
    private final ProtecaoSmtp protecao = new ProtecaoSmtp(circuito,
            new SmtpProperties(true, 1, 1, 100, Duration.ofMillis(1), Duration.ofMillis(1)), sonda);

    @Test
    void abrePorConexaoRecusadaEBloqueiaEntregasAteSondaRecuperar() throws Exception {
        Runnable envio = mock(Runnable.class);
        assertThatThrownBy(() -> protecao.executar(() -> { throw conexao(); }))
                .isInstanceOf(SmtpAguardandoRecuperacaoException.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> protecao.executar(envio)).isInstanceOf(SmtpAguardandoRecuperacaoException.class);
        verifyNoInteractions(envio);
        doThrow(new MessagingException("Sem conexão")).when(sonda).verificar();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            protecao.sondar();
            verify(sonda, atLeastOnce()).verificar();
        });
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        doNothing().when(sonda).verificar();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            protecao.sondar();
            assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
        protecao.executar(envio);
        verify(envio).run();
    }

    @ParameterizedTest
    @ValueSource(ints = {450, 451, 452, 550, 535})
    void rejeicoesSmtpContinuamNoFluxoDeRetryEDlqSemAbrirCircuito(int codigo) throws Exception {
        var falha = new MailSendException("Rejeição", new SMTPAddressFailedException(
                new InternetAddress("cliente@example.com"), "RCPT TO", codigo, "resposta"));
        assertThatThrownBy(() -> protecao.executar(() -> { throw falha; })).isSameAs(falha);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verifyNoInteractions(sonda);
    }

    @Test
    void resposta421AbreCircuitoMasFalhaDeBancoNaoAbre() {
        assertThatThrownBy(() -> protecao.executar(() -> { throw new QueryTimeoutException("Banco"); }))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThatThrownBy(() -> protecao.executar(() -> { throw new MailSendException("SMTP", new MessagingException("421 unavailable")); }))
                .isInstanceOf(SmtpAguardandoRecuperacaoException.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void transporteDentroDeRejeicao5xxNaoAbreCircuito() throws Exception {
        var falha = new SMTPAddressFailedException(new InternetAddress("cliente@example.com"), "RCPT", 550, "rejeitado");
        falha.setNextException(new ConnectException());
        assertThat(classificador.indisponibilidadeSmtp(new MailSendException("SMTP", falha))).isFalse();
    }

    @Test
    void desabilitadoNaoBloqueiaNemSonda() {
        var desabilitado = new ProtecaoSmtp(circuito,
                new SmtpProperties(false, 1, 1, 100, Duration.ofSeconds(1), Duration.ofMillis(1)), sonda);
        circuito.transitionToOpenState();
        var envio = mock(Runnable.class);
        desabilitado.executar(envio); desabilitado.sondar();
        verify(envio).run(); verifyNoInteractions(sonda);
    }

    private MailSendException conexao() { return new MailSendException("SMTP", new ConnectException()); }
}
