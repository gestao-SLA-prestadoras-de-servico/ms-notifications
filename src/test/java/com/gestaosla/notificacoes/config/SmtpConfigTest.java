package com.gestaosla.notificacoes.config;

import java.net.ConnectException;
import java.time.Duration;
import com.gestaosla.notificacoes.eventos.ClassificadorFalha;
import com.gestaosla.notificacoes.integracoes.ProtecaoSmtp;
import com.gestaosla.notificacoes.integracoes.SmtpAguardandoRecuperacaoException;
import com.gestaosla.notificacoes.integracoes.SondaSmtp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpConfigTest {
    @Test
    void minimoConfiguradoMantemFalhaAnteriorAAberturaNoRetryLimitado() {
        var properties = new SmtpProperties(true, 3, 2, 100, Duration.ofSeconds(17), Duration.ofSeconds(2));
        var circuito = new SmtpConfig().smtpCircuitBreaker(properties, new ClassificadorFalha());
        var protecao = new ProtecaoSmtp(circuito, properties, mock(SondaSmtp.class));
        var falha = new MailSendException("SMTP", new ConnectException());
        assertThatThrownBy(() -> protecao.executar(() -> { throw falha; })).isSameAs(falha);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThatThrownBy(() -> protecao.executar(() -> { throw falha; }))
                .isInstanceOf(SmtpAguardandoRecuperacaoException.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuito.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(17000L);
    }
}
