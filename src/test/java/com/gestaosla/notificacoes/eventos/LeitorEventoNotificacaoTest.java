package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contrato de backend/contratos/notificacoes (outbox-04), validado pelos schemas copiados. */
class LeitorEventoNotificacaoTest {
    private static final Path EXEMPLOS = Path.of("docs/contratos/exemplos");
    private static final Path SCHEMAS_BACKEND = Path.of("../backend/contratos/notificacoes");
    private static final Path SCHEMAS_MS = Path.of("src/main/resources/contratos/notificacoes");
    private static final LeitorEventoNotificacao LEITOR = new LeitorEventoNotificacao();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static Stream<Path> validos() throws IOException {
        return arquivos("validos");
    }

    static Stream<Path> invalidos() throws IOException {
        return arquivos("invalidos");
    }

    private static Stream<Path> arquivos(String pasta) throws IOException {
        try (var paths = Files.list(EXEMPLOS.resolve(pasta))) {
            return paths.filter(path -> path.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("validos")
    void aceitaTodosOsExemplosDocumentados(Path path) throws IOException {
        String json = Files.readString(path);
        var evento = LEITOR.ler(json);
        assertThat(LEITOR.ler(json, evento.tipo().canal())).isEqualTo(evento);
        assertThat(evento.chamado().numero()).isEqualTo(1042L);
        assertThat(evento.chamado().exibicao()).isEqualTo("#1042");
        assertThat(evento.tipo().canal().routingKey()).isEqualTo(
                Set.of(TipoEvento.POSICIONAMENTO, TipoEvento.RESOLUCAO,
                        TipoEvento.AVISO_ENCERRAMENTO).contains(evento.tipo()) ? "email" : "interna");
    }

    @Test
    void exemplosCobremCadaTipoUmaVez() throws IOException {
        try (var paths = validos()) {
            var tipos = paths.map(path -> {
                try {
                    return LEITOR.ler(Files.readString(path)).tipo();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }).toList();
            assertThat(tipos).containsExactlyInAnyOrder(TipoEvento.values());
        }
    }

    @Test
    void schemasSaoCopiaFielDosDoBackend() throws IOException {
        // Pula fora do monorepo (ex.: CI só do ms); lá a cópia é conferida na integração.
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(SCHEMAS_BACKEND));
        try (var paths = Files.list(SCHEMAS_BACKEND)) {
            for (Path backend : paths.toList()) {
                assertThat(SCHEMAS_MS.resolve(backend.getFileName())).hasSameTextualContentAs(backend);
            }
        }
        try (var paths = Files.list(SCHEMAS_MS)) {
            assertThat(paths.count()).isEqualTo(TipoEvento.values().length);
        }
    }

    @Test
    void leCamposProprios() throws IOException {
        var violacao = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/violacao.json")));
        assertThat(violacao.relogio()).isEqualTo(Relogio.ATENDIMENTO_CAMPO);
        assertThat(violacao.destinatarios()).hasSize(2);
        var aviso = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/aviso-encerramento.json")));
        assertThat(aviso.encerramentoPrevistoEm()).isEqualTo(OffsetDateTime.parse("2026-11-12T20:00:00Z"));
        var posicionamento = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/posicionamento.json")));
        assertThat(posicionamento.textoPosicionamento()).isNotBlank();
    }

    @Test
    void aceitaChavesEmQualquerOrdem() throws IOException {
        // A coluna jsonb do backend não preserva a ordem das chaves.
        ObjectNode original = (ObjectNode) MAPPER.readTree(Files.readString(EXEMPLOS.resolve("validos/violacao.json")));
        ObjectNode invertido = MAPPER.createObjectNode();
        var nomes = new java.util.ArrayList<>(original.propertyNames());
        java.util.Collections.reverse(nomes);
        nomes.forEach(nome -> invertido.set(nome, original.get(nome)));
        assertThat(LEITOR.ler(invertido.toString())).isEqualTo(LEITOR.ler(original.toString()));
    }

    @ParameterizedTest
    @MethodSource("invalidos")
    void rejeitaExemplosInvalidosSemExporPayload(Path path) throws IOException {
        rejeita(Files.readString(path));
    }

    @Test
    void rejeitaCamposObrigatoriosNulos() throws IOException {
        String base = Files.readString(EXEMPLOS.resolve("validos/atribuicao.json"));
        for (String campo : Set.of("eventoId", "tipo", "chamado", "titulo", "destinatarios")) {
            ObjectNode node = (ObjectNode) MAPPER.readTree(base);
            node.putNull(campo);
            rejeita(node.toString());
        }
        for (String campo : Set.of("id", "numero")) {
            for (String valor : Arrays.asList(null, "", " ", "1042")) {
                ObjectNode node = (ObjectNode) MAPPER.readTree(base);
                ((ObjectNode) node.get("chamado")).put(campo, valor);
                rejeita(node.toString());
            }
            ObjectNode node = (ObjectNode) MAPPER.readTree(base);
            ((ObjectNode) node.get("chamado")).remove(campo);
            rejeita(node.toString());
        }
    }

    @Test
    void camposPropriosSoValemNoProprioTipo() throws IOException {
        String atribuicao = Files.readString(EXEMPLOS.resolve("validos/atribuicao.json"));
        for (String campo : Set.of("relogio", "textoPosicionamento", "encerramentoPrevistoEm")) {
            ObjectNode node = (ObjectNode) MAPPER.readTree(atribuicao);
            node.put(campo, "RESPOSTA");
            rejeita(node.toString());
        }
        // O posicionamento não vira outro tipo de e-mail só trocando o tipo.
        String posicionamento = Files.readString(EXEMPLOS.resolve("validos/posicionamento.json"));
        for (TipoEvento tipo : TipoEvento.values()) {
            if (tipo != TipoEvento.POSICIONAMENTO) {
                ObjectNode node = (ObjectNode) MAPPER.readTree(posicionamento);
                node.put("tipo", tipo.name());
                rejeita(node.toString());
            }
        }
    }

    @Test
    void rejeitaJsonMalformadoTiposIncorretosECamposDesconhecidos() throws IOException {
        for (String json : Arrays.asList(null, "", " ", "null", "[]", "{", "{}", "{} {}")) {
            rejeita(json);
        }
        String base = Files.readString(EXEMPLOS.resolve("validos/atribuicao.json"));
        for (String campo : Set.of("eventoId", "tipo", "chamado", "titulo", "destinatarios")) {
            ObjectNode node = (ObjectNode) MAPPER.readTree(base);
            node.put(campo, 1);
            rejeita(node.toString());
        }
        rejeita(base + " {}");
        rejeita(base.replace("\"ATRIBUICAO\"", "\"atribuicao\""));
    }

    @Test
    void validaEmailEmTodosOsTiposDoCanal() throws IOException {
        for (String arquivo : Set.of("posicionamento", "resolucao", "aviso-encerramento")) {
            String base = Files.readString(EXEMPLOS.resolve("validos/" + arquivo + ".json"));
            rejeita(base.replace("contato@boamassa.com.br", "email-invalido"));
            rejeita(base.replace("\"contato@boamassa.com.br\"", "null"));
            rejeita(base.replace("\"contato@boamassa.com.br\"", "123"));
        }
    }

    @Test
    void rejeitaCanalIncorreto() throws IOException {
        String json = Files.readString(EXEMPLOS.resolve("validos/resolucao.json"));
        assertThatThrownBy(() -> LEITOR.ler(json, CanalNotificacao.INTERNA))
                .isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    void toStringNaoExibeDadosPessoais() throws IOException {
        var evento = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/posicionamento.json")));
        assertThat(evento.toString()).doesNotContain(evento.destinatarios().getFirst(), evento.textoPosicionamento());
    }

    private static void rejeita(String payload) {
        assertThatThrownBy(() -> LEITOR.ler(payload))
                .isInstanceOf(EventoInvalidoException.class)
                .hasMessage("Mensagem de notificação inválida")
                .hasNoCause();
    }
}
