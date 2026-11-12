package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessadorMensagemNotificacaoTest {
    @Test
    void validaAntesDeProcessarOuConfirmar() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var core = mock(ProcessadorEventoIdempotente.class);
            var channel = mock(Channel.class);
            var processor = new ProcessadorMensagemNotificacao(new LeitorEventoNotificacao(factory.getValidator()), core);
            assertThatThrownBy(() -> processor.processar(message("{}"), channel, CanalNotificacao.INTERNA, e -> fail("Não executar")))
                    .isInstanceOf(EventoInvalidoException.class);
            assertThatThrownBy(() -> processor.processar(message(fixture()), channel, CanalNotificacao.EMAIL, e -> fail("Não executar")))
                    .isInstanceOf(EventoInvalidoException.class);
            verifyNoInteractions(core, channel);
        }
    }

    @Test
    void confirmaSomenteAEntregaAtualAposRetornoDoProcessador() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var core = mock(ProcessadorEventoIdempotente.class);
            var channel = mock(Channel.class);
            var action = new AtomicInteger();
            when(core.processar(anyString(), any(), any())).thenAnswer(invocation -> {
                verifyNoInteractions(channel);
                ((Runnable) invocation.getArgument(2)).run();
                return ResultadoProcessamento.PROCESSADO;
            });
            var processor = new ProcessadorMensagemNotificacao(new LeitorEventoNotificacao(factory.getValidator()), core);
            assertThat(processor.processar(message(fixture()), channel, CanalNotificacao.INTERNA, e -> action.incrementAndGet()))
                    .isEqualTo(ResultadoProcessamento.PROCESSADO);
            assertThat(action).hasValue(1);
            verify(channel).basicAck(42L, false);
            verifyNoMoreInteractions(channel);
        }
    }

    @Test
    void confirmaRepeticaoSemExecutarAcao() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var core = mock(ProcessadorEventoIdempotente.class);
            when(core.processar(anyString(), any(), any())).thenReturn(ResultadoProcessamento.JA_PROCESSADO);
            var channel = mock(Channel.class);
            var processor = new ProcessadorMensagemNotificacao(new LeitorEventoNotificacao(factory.getValidator()), core);
            assertThat(processor.processar(message(fixture()), channel, CanalNotificacao.INTERNA, e -> fail("Não executar")))
                    .isEqualTo(ResultadoProcessamento.JA_PROCESSADO);
            verify(channel).basicAck(42L, false);
        }
    }

    @Test
    void falhaDeProcessamentoNaoConfirmaMensagem() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var core = mock(ProcessadorEventoIdempotente.class);
            when(core.processar(anyString(), any(), any())).thenThrow(new IllegalStateException("Falha simulada"));
            var channel = mock(Channel.class);
            var processor = new ProcessadorMensagemNotificacao(new LeitorEventoNotificacao(factory.getValidator()), core);
            assertThatThrownBy(() -> processor.processar(message(fixture()), channel, CanalNotificacao.INTERNA, e -> { }))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(channel);
        }
    }

    @Test
    void propagaFalhaNoAckSemReexecutarProcessamento() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var core = mock(ProcessadorEventoIdempotente.class);
            when(core.processar(anyString(), any(), any())).thenReturn(ResultadoProcessamento.PROCESSADO);
            var channel = mock(Channel.class);
            doThrow(new IOException("Conexão perdida")).when(channel).basicAck(42L, false);
            var processor = new ProcessadorMensagemNotificacao(new LeitorEventoNotificacao(factory.getValidator()), core);
            assertThatThrownBy(() -> processor.processar(message(fixture()), channel, CanalNotificacao.INTERNA, e -> { }))
                    .isInstanceOf(IOException.class);
            verify(core, times(1)).processar(anyString(), any(), any());
        }
    }

    private String fixture() throws IOException {
        return Files.readString(Path.of("docs/contratos/exemplos/validos/atribuicao.json"));
    }

    private Message message(String body) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }
}
