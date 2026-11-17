package com.gestaosla.notificacoes.integracoes;

import java.time.OffsetDateTime;
import java.util.List;

import com.gestaosla.notificacoes.eventos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class TemplateEmailClienteTest {
    private final TemplateEmailCliente template = new TemplateEmailCliente();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"POSICIONAMENTO|Primeiro posicionamento", "RESOLUCAO|Chamado resolvido", "AVISO_ENCERRAMENTO|Validação automática próxima"})
    void possuiAssuntoEConteudoDoEvento(TipoEvento tipo, String assunto) {
        var content = template.compor(evento(tipo, "Equipamento parado", "Chegaremos às 14h."));
        assertThat(content.assunto()).contains(assunto, "#1042", "Equipamento parado");
        assertThat(content.texto()).contains("#1042", "Equipamento parado");
        assertThat(content.html()).contains("#1042", "Equipamento parado");
        if (tipo == TipoEvento.POSICIONAMENTO) {
            assertThat(content.texto()).contains("Chegaremos às 14h.");
            assertThat(content.html()).contains("Chegaremos às 14h.");
        } else {
            assertThat(content.texto()).doesNotContain("Chegaremos às 14h.");
        }
    }

    @Test
    void avisoDeEncerramentoMostraDataNoHorarioDeBrasilia() {
        var content = template.compor(evento(TipoEvento.AVISO_ENCERRAMENTO, "Equipamento parado", null));
        // 2026-11-12T20:00Z = 17:00 em Brasília (UTC-3, sem horário de verão).
        assertThat(content.texto()).contains("12/11/2026 às 17:00 (horário de Brasília)");
        assertThat(content.html()).contains("12/11/2026 às 17:00");
    }

    @Test
    void escapaTituloNumeroEPosicionamentoSemEscaparVersaoTexto() {
        var event = new EventoNotificacao("evento", TipoEvento.POSICIONAMENTO, new ChamadoEvento("id", 1042),
                "<img src=x> & \"ação\"", List.of("cliente@example.com"), null, "Linha 1\r\n<script>alert('x')</script>", null);
        var content = template.compor(event);
        assertThat(content.html()).contains("&lt;img src=x&gt;", "&amp;", "&quot;", "Linha 1<br>&lt;script&gt;")
                .doesNotContain("<img", "<script>");
        assertThat(content.texto()).contains(event.titulo(), event.textoPosicionamento(), event.chamado().exibicao());
    }

    @Test
    void removeQuebrasDeLinhaDoAssunto() {
        var event = new EventoNotificacao("evento", TipoEvento.RESOLUCAO, new ChamadoEvento("id", 1042),
                "Título\r\nBcc: outro@example.com\nX-Injected: valor", List.of("cliente@example.com"), null, null, null);
        assertThat(template.compor(event).assunto()).doesNotContain("\r", "\n");
    }

    @ParameterizedTest
    @EnumSource(value = TipoEvento.class, names = {"ATRIBUICAO", "REMOCAO", "REABERTURA", "CONCLUSAO", "VIOLACAO"})
    void rejeitaTiposInternos(TipoEvento tipo) {
        assertThatThrownBy(() -> template.compor(evento(tipo, "Título", null))).isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    void toStringNaoExibeConteudoDoCliente() {
        var content = template.compor(evento(TipoEvento.POSICIONAMENTO, "Título privado", "Texto privado"));
        assertThat(content.toString()).doesNotContain("Título privado", "Texto privado");
    }

    private EventoNotificacao evento(TipoEvento tipo, String titulo, String texto) {
        return new EventoNotificacao("evento", tipo, new ChamadoEvento("id", 1042), titulo, List.of("cliente@example.com"), null,
                texto, tipo == TipoEvento.AVISO_ENCERRAMENTO ? OffsetDateTime.parse("2026-11-12T20:00:00Z") : null);
    }
}
