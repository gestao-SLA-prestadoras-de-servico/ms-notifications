package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.dao.QueryTimeoutException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessadorEntregaTest {
    private final ProcessadorMensagemNotificacao core = mock(ProcessadorMensagemNotificacao.class);
    private final PublicadorEncaminhamento publisher = mock(PublicadorEncaminhamento.class);
    private final Channel channel = mock(Channel.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry metricas = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final ProcessadorEntrega processor = new ProcessadorEntrega(core, new ClassificadorFalha(), publisher, "retry", "dlx", metricas);

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4})
    void circuitoAbertoDevolveSemAlterarTentativaOuEncaminhar(int attempt) throws Exception {
        when(core.processar(any(), any(), any(), any()))
                .thenThrow(new com.gestaosla.notificacoes.integracoes.SmtpAguardandoRecuperacaoException());
        var original = message(attempt);
        processor.consumir(original, channel, CanalNotificacao.EMAIL, e -> { });
        verify(channel).basicNack(42L, false, true);
        verifyNoInteractions(publisher);
        assertThat(original.getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(attempt);
        assertThat(metricas.get("notificacoes.falhas").tags("canal", "email", "categoria", "indisponibilidade_smtp").counter().count()).isEqualTo(1);
    }

    @Test
    void contabilizaEntregasEFalhasPorCanalSemEtiquetasPessoais() throws Exception {
        when(core.processar(any(), any(), any(), any())).thenReturn(ResultadoProcessamento.PROCESSADO)
                .thenReturn(ResultadoProcessamento.JA_PROCESSADO).thenThrow(new QueryTimeoutException("falha"));
        processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { });
        processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { });
        processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { });
        assertThat(metricas.get("notificacoes.entregas").tags("canal", "email", "resultado", "processado").counter().count()).isEqualTo(1);
        assertThat(metricas.get("notificacoes.entregas").tags("canal", "email", "resultado", "duplicado").counter().count()).isEqualTo(1);
        assertThat(metricas.get("notificacoes.falhas").tags("canal", "email", "categoria", "temporaria").counter().count()).isEqualTo(1);
        assertThat(metricas.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags()).allSatisfy(tag ->
                assertThat(tag.getKey()).isIn("canal", "resultado", "categoria")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void publicaRetryPersistenteComProximaTentativaAntesDeAck(int attempt) throws Exception {
        when(core.processar(any(), any(), any(), any())).thenThrow(new QueryTimeoutException("Falha"));
        var original = message(attempt);
        processor.consumir(original, channel, CanalNotificacao.EMAIL, e -> { });
        var copy = ArgumentCaptor.forClass(Message.class);
        var order = inOrder(publisher, channel);
        order.verify(publisher).publicar(eq("retry"), eq("email." + attempt), copy.capture());
        order.verify(channel).basicAck(42L, false);
        assertThat(copy.getValue().getBody()).isEqualTo(original.getBody());
        assertThat(copy.getValue().getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(attempt + 1);
        assertThat(copy.getValue().getMessageProperties().getExpiration()).isNull();
        assertThat(copy.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(original.getMessageProperties().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(attempt);
        verify(core, times(1)).processar(any(), any(), any(), any());
    }

    @Test
    void quartaFalhaTemporariaEsgotaEPublicaNaDlq() throws Exception {
        when(core.processar(any(), any(), any(), any())).thenThrow(new QueryTimeoutException("Falha"));
        processor.consumir(message(4), channel, CanalNotificacao.INTERNA, e -> { });
        var order = inOrder(publisher, channel);
        order.verify(publisher).publicar(eq("dlx"), eq("interna"), any());
        order.verify(channel).basicAck(42L, false);
    }

    @Test
    void erroDefinitivoSegueDiretoParaDlq() throws Exception {
        when(core.processar(any(), any(), any(), any())).thenThrow(new EventoInvalidoException());
        processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { });
        verify(publisher).publicar(eq("dlx"), eq("email"), any());
        verify(channel).basicAck(42L, false);
    }

    @Test
    void falhaDeEncaminhamentoMantemOrigemSemAck() throws Exception {
        when(core.processar(any(), any(), any(), any())).thenThrow(new EventoInvalidoException());
        doThrow(new IOException("Sem confirmação")).when(publisher).publicar(anyString(), anyString(), any());
        processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { });
        verify(channel).basicNack(42L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(metricas.get("notificacoes.falhas").tags("canal", "email", "categoria", "encaminhamento").counter().count()).isEqualTo(1);
    }

    @Test void falhaNoAckDoEncaminhamentoEContadaComoTransporte() throws Exception {
        when(core.processar(any(), any(), any(), any())).thenThrow(new EventoInvalidoException());
        doThrow(new IOException("Falha ACK")).when(channel).basicAck(42L, false);
        assertThatThrownBy(() -> processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { })).isInstanceOf(IOException.class);
        assertThat(metricas.get("notificacoes.falhas").tags("canal", "email", "categoria", "transporte").counter().count()).isEqualTo(1);
    }

    @Test
    void erroDeAckDepoisDoCommitNaoEVistoComoFalhaDeNegocio() throws Exception {
        when(core.processar(any(), any(), any(), any())).thenThrow(new IOException("ACK perdido"));
        assertThatThrownBy(() -> processor.consumir(message(1), channel, CanalNotificacao.EMAIL, e -> { })).isInstanceOf(IOException.class);
        verifyNoInteractions(publisher, channel);
    }

    @Test
    void cabecalhoInvalidoNaoExecutaAcao() throws Exception {
        var message = message(1);
        message.getMessageProperties().setHeader(ProcessadorEntrega.TENTATIVA, "zero");
        processor.consumir(message, channel, CanalNotificacao.EMAIL, e -> fail("Não executar"));
        verifyNoInteractions(core);
        verify(publisher).publicar(eq("dlx"), eq("email"), any());
    }

    private Message message(int attempt) {
        var props = new MessageProperties();
        props.setDeliveryTag(42L); props.setExpiration("10");
        props.setHeader(ProcessadorEntrega.TENTATIVA, attempt);
        return new Message(new byte[] {1, 2, 3}, props);
    }
}
