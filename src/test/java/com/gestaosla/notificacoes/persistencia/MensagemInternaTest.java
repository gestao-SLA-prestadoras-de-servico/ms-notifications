package com.gestaosla.notificacoes.persistencia;

import java.time.Instant;

import com.gestaosla.notificacoes.eventos.Relogio;
import com.gestaosla.notificacoes.eventos.TipoEvento;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class MensagemInternaTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ATRIBUICAO|Você foi atribuído ao chamado #1042 — Equipamento parado.",
            "REMOCAO|Você foi removido do chamado #1042 — Equipamento parado.",
            "REABERTURA|O chamado #1042 — Equipamento parado foi reaberto.",
            "CONCLUSAO|O atendimento do chamado #1042 — Equipamento parado foi concluído.",
            "VIOLACAO|O chamado #1042 — Equipamento parado teve o prazo de SLA de atendimento em campo violado."
    })
    void compoeTextoPorTipoSemMudarTitulo(TipoEvento tipo, String esperado) {
        var notificacao = nova(tipo);
        assertThat(notificacao.getMensagem()).isEqualTo(esperado);
        assertThat(notificacao.getTitulo()).isEqualTo("Equipamento parado");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"RESPOSTA|resposta", "ATENDIMENTO_CAMPO|atendimento em campo", "RESOLUCAO|resolução"})
    void citaORelogioViolado(Relogio relogio, String descricao) {
        var notificacao = new NotificacaoInterna("sub", "evento", TipoEvento.VIOLACAO, "1042", "#1042", "Equipamento parado",
                relogio, Instant.EPOCH);
        assertThat(notificacao.getMensagem()).contains("SLA de " + descricao + " violado");
    }

    @ParameterizedTest
    @EnumSource(value = TipoEvento.class, names = {"POSICIONAMENTO", "RESOLUCAO", "AVISO_ENCERRAMENTO"})
    void naoCompoeMensagemInternaParaEmail(TipoEvento tipo) {
        assertThatThrownBy(() -> nova(tipo).getMensagem()).isInstanceOf(IllegalStateException.class);
    }

    private NotificacaoInterna nova(TipoEvento tipo) {
        return new NotificacaoInterna("sub", "evento", tipo, "1042", "#1042", "Equipamento parado",
                tipo == TipoEvento.VIOLACAO ? Relogio.ATENDIMENTO_CAMPO : null, Instant.EPOCH);
    }
}
