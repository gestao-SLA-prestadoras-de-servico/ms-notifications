package com.gestaosla.notificacoes.integracoes;

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
    @CsvSource(delimiter = '|', value = {"PRIMEIRO_POSICIONAMENTO|Primeiro posicionamento", "RESOLUCAO|Chamado resolvido", "AVISO_ENCERRAMENTO|Validação automática próxima"})
    void possuiAssuntoEConteudoDoEvento(TipoEvento tipo, String assunto) {
        var content = template.compor(evento(tipo, "Equipamento parado", "Chegaremos às 14h."));
        assertThat(content.assunto()).contains(assunto, "#1042", "Equipamento parado");
        assertThat(content.texto()).contains("#1042", "Equipamento parado");
        assertThat(content.html()).contains("#1042", "Equipamento parado");
        if (tipo == TipoEvento.PRIMEIRO_POSICIONAMENTO) {
            assertThat(content.texto()).contains("Chegaremos às 14h.");
            assertThat(content.html()).contains("Chegaremos às 14h.");
        } else {
            assertThat(content.texto()).doesNotContain("Chegaremos às 14h.");
        }
    }

    @Test
    void escapaTituloNumeroEPosicionamentoSemEscaparVersaoTexto() {
        var event = new EventoNotificacao("evento", TipoEvento.PRIMEIRO_POSICIONAMENTO,
                new ChamadoEvento("id", "#1042 <b>"), "<img src=x> & \"ação\"", List.of("cliente@example.com"), "Linha 1\r\n<script>alert('x')</script>");
        var content = template.compor(event);
        assertThat(content.html()).contains("&lt;b&gt;", "&lt;img src=x&gt;", "&amp;", "&quot;", "Linha 1<br>&lt;script&gt;")
                .doesNotContain("<img", "<script>");
        assertThat(content.texto()).contains(event.titulo(), event.textoPosicionamento(), event.chamado().numero());
    }

    @Test
    void removeQuebrasDeLinhaDoAssunto() {
        var event = new EventoNotificacao("evento", TipoEvento.RESOLUCAO, new ChamadoEvento("id", "#1042\r\nBcc: outro@example.com"),
                "Título\nX-Injected: valor", List.of("cliente@example.com"), null);
        assertThat(template.compor(event).assunto()).doesNotContain("\r", "\n");
    }

    @ParameterizedTest
    @EnumSource(value = TipoEvento.class, names = {"ATRIBUICAO", "REATRIBUICAO", "REMOCAO", "REABERTURA", "CONCLUSAO", "VIOLACAO_SLA"})
    void rejeitaTiposInternos(TipoEvento tipo) {
        assertThatThrownBy(() -> template.compor(evento(tipo, "Título", null))).isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    void toStringNaoExibeConteudoDoCliente() {
        var content = template.compor(evento(TipoEvento.PRIMEIRO_POSICIONAMENTO, "Título privado", "Texto privado"));
        assertThat(content.toString()).doesNotContain("Título privado", "Texto privado");
    }

    private EventoNotificacao evento(TipoEvento tipo, String titulo, String texto) {
        return new EventoNotificacao("evento", tipo, new ChamadoEvento("id", "#1042"), titulo, List.of("cliente@example.com"), texto);
    }
}
