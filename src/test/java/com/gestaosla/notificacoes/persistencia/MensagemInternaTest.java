package com.gestaosla.notificacoes.persistencia;

import java.time.Instant;

import com.gestaosla.notificacoes.eventos.TipoEvento;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class MensagemInternaTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ATRIBUICAO|Você foi atribuído ao chamado #1042 — Equipamento parado.",
            "REATRIBUICAO|O chamado #1042 — Equipamento parado foi reatribuído a você.",
            "REMOCAO|Você foi removido do chamado #1042 — Equipamento parado.",
            "REABERTURA|O chamado #1042 — Equipamento parado foi reaberto.",
            "CONCLUSAO|O atendimento do chamado #1042 — Equipamento parado foi concluído.",
            "VIOLACAO_SLA|O chamado #1042 — Equipamento parado teve um prazo de SLA violado."
    })
    void compoeTextoPorTipoSemMudarTitulo(TipoEvento tipo, String esperado) {
        var notificacao = nova(tipo);
        assertThat(notificacao.getMensagem()).isEqualTo(esperado);
        assertThat(notificacao.getTitulo()).isEqualTo("Equipamento parado");
    }

    @ParameterizedTest
    @EnumSource(value = TipoEvento.class, names = {"PRIMEIRO_POSICIONAMENTO", "RESOLUCAO", "AVISO_ENCERRAMENTO"})
    void naoCompoeMensagemInternaParaEmail(TipoEvento tipo) {
        assertThatThrownBy(() -> nova(tipo).getMensagem()).isInstanceOf(IllegalStateException.class);
    }

    private NotificacaoInterna nova(TipoEvento tipo) {
        return new NotificacaoInterna("sub", "evento", tipo, "1042", "#1042", "Equipamento parado", Instant.EPOCH);
    }
}
