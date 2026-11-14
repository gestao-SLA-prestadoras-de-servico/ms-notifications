package com.gestaosla.notificacoes.eventos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeitorEventoNotificacaoTest {
    private static final Path EXEMPLOS = Path.of("docs/contratos/exemplos");
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final LeitorEventoNotificacao LEITOR = new LeitorEventoNotificacao(FACTORY.getValidator());
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @AfterAll
    static void fecharValidator() {
        FACTORY.close();
    }

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
        assertThat(evento.chamado().numero()).isEqualTo("#1042");
        assertThat(evento.tipo().canal().routingKey()).isEqualTo(
                Set.of(TipoEvento.PRIMEIRO_POSICIONAMENTO, TipoEvento.RESOLUCAO,
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

    @ParameterizedTest
    @MethodSource("invalidos")
    void rejeitaExemplosInvalidosSemExporPayload(Path path) throws IOException {
        rejeita(Files.readString(path));
    }

    @Test
    void rejeitaCamposObrigatoriosAusentesNulosOuVazios() throws IOException {
        String base = Files.readString(EXEMPLOS.resolve("validos/atribuicao.json"));
        for (String campo : Set.of("eventoId", "tipo", "chamado", "titulo", "destinatarios")) {
            ObjectNode node = (ObjectNode) MAPPER.readTree(base);
            node.putNull(campo);
            rejeita(node.toString());
        }
        for (String campo : Set.of("id", "numero")) {
            for (String valor : Arrays.asList(null, "", " ")) {
                ObjectNode node = (ObjectNode) MAPPER.readTree(base);
                ((ObjectNode) node.get("chamado")).put(campo, valor);
                rejeita(node.toString());
            }
            ObjectNode node = (ObjectNode) MAPPER.readTree(base);
            ((ObjectNode) node.get("chamado")).remove(campo);
            rejeita(node.toString());
        }
        rejeita(base.replace("evento-1", " "));
    }

    @Test
    void validaTextoDoPosicionamentoCondicionalmente() throws IOException {
        String base = Files.readString(EXEMPLOS.resolve("validos/primeiro-posicionamento.json"));
        for (String texto : Arrays.asList(null, "", " ")) {
            ObjectNode node = (ObjectNode) MAPPER.readTree(base);
            node.put("textoPosicionamento", texto);
            rejeita(node.toString());
        }
        for (TipoEvento tipo : TipoEvento.values()) {
            if (tipo != TipoEvento.PRIMEIRO_POSICIONAMENTO) {
                ObjectNode node = (ObjectNode) MAPPER.readTree(base);
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
        ObjectNode node = (ObjectNode) MAPPER.readTree(base);
        node.put("campoDesconhecido", "valor");
        rejeita(node.toString());
        rejeita(base + " {}");
        rejeita(base.replace("ATRIBUICAO", "0"));
        rejeita(base.replace("ATRIBUICAO", "atribuicao"));
    }

    @Test
    void validaEmailEmTodosOsTiposDoCanal() throws IOException {
        for (String arquivo : Set.of("primeiro-posicionamento", "resolucao", "aviso-encerramento")) {
            String base = Files.readString(EXEMPLOS.resolve("validos/" + arquivo + ".json"));
            rejeita(base.replace("cliente@example.com", "email-invalido"));
            rejeita(base.replace("\"cliente@example.com\"", "null"));
            rejeita(base.replace("\"cliente@example.com\"", "123"));
        }
    }

    @Test
    void preservaIdentidadeOpacaEDestinatariosDefinidosPeloProdutor() throws IOException {
        var violacao = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/violacao-sla.json")));
        assertThat(violacao.eventoId()).isEqualTo("sla:1042:ATENDIMENTO:2026-11-11T11:00:00Z");
        assertThat(violacao.destinatarios()).containsExactly("sub-gestor-1", "sub-gestor-2", "sub-tecnico-atribuido");
        var transferencia = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/reatribuicao.json")));
        assertThat(transferencia.destinatarios()).containsExactly("sub-tecnico-substituto");
    }

    @Test
    void rejeitaCanalIncorreto() throws IOException {
        String json = Files.readString(EXEMPLOS.resolve("validos/resolucao.json"));
        assertThatThrownBy(() -> LEITOR.ler(json, CanalNotificacao.INTERNA))
                .isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    void toStringNaoExibeDadosPessoais() throws IOException {
        var evento = LEITOR.ler(Files.readString(EXEMPLOS.resolve("validos/primeiro-posicionamento.json")));
        assertThat(evento.toString()).doesNotContain(evento.destinatarios().getFirst(), evento.textoPosicionamento());
    }

    private static void rejeita(String payload) {
        assertThatThrownBy(() -> LEITOR.ler(payload))
                .isInstanceOf(EventoInvalidoException.class)
                .hasMessage("Mensagem de notificação inválida")
                .hasNoCause();
    }
}
