package com.gestaosla.notificacoes.eventos;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Experimento de escala horizontal com carga, recursos por instância e latência constantes. */
class EscalabilidadeIT {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final int eventos = parametro("escala.eventos", 32, 16, 1000);
    private final int latencia = parametro("escala.latencia-ms", 100, 1, 2000);
    private final int repeticoes = parametro("escala.repeticoes", 2, 1, 5);
    private final Path raiz = Path.of("target/ms-not-16", UUID.randomUUID().toString());

    @Test @Timeout(1200) void compararUmaEQuatroInstanciasSemPresumirGanho() throws Exception {
        Files.createDirectories(raiz);
        List<Map<String, Object>> resultados = new ArrayList<>();
        List<byte[]> carga = carga();
        var hash = MessageDigest.getInstance("SHA-256");
        for (byte[] evento : carga) { hash.update(evento); hash.update((byte) '\n'); }
        var manifesto = new LinkedHashMap<String, Object>();
        manifesto.put("inicio", Instant.now().toString()); manifesto.put("eventosUnicos", eventos);
        manifesto.put("entregas", carga.size()); manifesto.put("sha256Carga", HexFormat.of().formatHex(hash.digest()));
        manifesto.put("latenciaDownstreamMs", latencia); manifesto.put("jitterMs", 0);
        manifesto.put("cpuPorInstancia", 1); manifesto.put("memoriaMiBPorInstancia", 768);
        manifesto.put("heapMaxMiB", 384); manifesto.put("concorrenciaPorCanal", 1); manifesto.put("prefetch", 1);
        manifesto.put("aquecimentoEventos", 8); manifesto.put("amostragemMs", 250);
        Path jar;
        try (var arquivos = Files.list(Path.of("target"))) {
            jar = arquivos.filter(p -> p.getFileName().toString().matches("ms-notificacoes-.*\\.jar")).findFirst().orElseThrow();
        }
        manifesto.put("sha256Jar", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        for (int repeticao = 1; repeticao <= repeticoes; repeticao++) {
            for (int instancias : repeticao % 2 == 1 ? new int[]{1, 4} : new int[]{4, 1}) {
                String nome = "r" + repeticao + "-" + instancias + "instancias";
                try (var rodada = new Rodada(raiz.resolve(nome), instancias, jar)) {
                    resultados.add(rodada.medir(carga, repeticao));
                    manifesto.put("ambienteDocker", rodada.ambienteDocker());
                } finally {
                    manifesto.put("resultados", resultados);
                    gravar(raiz.resolve("resultados.json"), manifesto);
                }
            }
        }
        // Um desempenho menor continua sendo um resultado válido. Só a integridade faz o teste falhar.
        assertThat(resultados).hasSize(repeticoes * 2);
        System.out.println("MS-NOT-16: resultados em " + raiz.toAbsolutePath());
    }

    private List<byte[]> carga() throws Exception {
        List<byte[]> carga = new ArrayList<>();
        for (int i = 0; i < eventos; i++) {
            byte[] payload = evento("carga-" + i, "email");
            carga.add(payload);
            if (i % 4 == 0) carga.add(payload); // Repetição imediata pode concorrer entre instâncias.
        }
        return carga;
    }
    private byte[] evento(String id, String canal) throws Exception {
        var node = (ObjectNode) json.readTree(Files.readString(Path.of("docs/contratos/exemplos/validos/"
                + (canal.equals("email") ? "resolucao" : "atribuicao") + ".json")));
        node.put("eventoId", id); node.put("titulo", "MSNOT16-" + id);
        node.putArray("destinatarios").add(canal.equals("email") ? id + "@example.test" : "sub-interno");
        return json.writeValueAsBytes(node);
    }

    private final class Rodada implements AutoCloseable {
        final Path pasta;
        final int instancias;
        final String senha = UUID.randomUUID().toString();
        final Network rede = Network.newNetwork();
        final List<GenericContainer<?>> recursos = new ArrayList<>();
        final List<GenericContainer<?>> apps = new ArrayList<>();
        GenericContainer<?> db, rabbit, mail, proxy;

        Rodada(Path pasta, int instancias, Path jar) throws Exception {
            this.pasta = pasta; this.instancias = instancias;
            Files.createDirectories(pasta);
            try {
                db = iniciar(limitar(new GenericContainer<>(DockerImageName.parse("postgres:17-alpine")), 2, 768)
                        .withNetworkAliases("postgres").withEnv("POSTGRES_DB", "notificacoes")
                        .withEnv("POSTGRES_USER", "teste").withEnv("POSTGRES_PASSWORD", senha).withExposedPorts(5432)
                        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)));
                rabbit = iniciar(limitar(new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-management-alpine")), 1, 768)
                        .withNetworkAliases("rabbitmq").withEnv("RABBITMQ_DEFAULT_USER", "teste").withEnv("RABBITMQ_DEFAULT_PASS", senha)
                        .withExposedPorts(5672, 15672).waitingFor(Wait.forHttp("/api/overview").forPort(15672).withBasicCredentials("teste", senha)));
                String topologia = Files.readString(Path.of("docs/topologia.json"));
                aguardar(() -> apiRabbit("POST", "/definitions", topologia));
                mail = iniciar(limitar(new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27")), 1, 256)
                        .withNetworkAliases("mailpit").withExposedPorts(8025).waitingFor(Wait.forHttp("/readyz").forPort(8025)));
                proxy = iniciar(limitar(new GenericContainer<>(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.13.0")), 0.5, 128)
                        .withNetworkAliases("toxiproxy").withExposedPorts(8474).waitingFor(Wait.forHttp("/version").forPort(8474)));
                requisicao("POST", url(proxy, 8474) + "/proxies", "{\"name\":\"smtp\",\"listen\":\"0.0.0.0:1025\",\"upstream\":\"mailpit:1025\"}", null);
                requisicao("POST", url(proxy, 8474) + "/proxies/smtp/toxics", json.writeValueAsString(Map.of(
                        "name", "latencia-smtp", "type", "latency", "stream", "downstream", "toxicity", 1,
                        "attributes", Map.of("latency", latencia, "jitter", 0))), null);
                gravar(pasta.resolve("toxiproxy.json"), json.readTree(requisicao("GET", url(proxy, 8474) + "/proxies/smtp", null, null)));
                // Migrações em sequência: a primeira instância prepara o schema; as demais partem do mesmo banco validado.
                for (int i = 0; i < instancias; i++) {
                    var app = limitar(new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre-alpine")), 1, 768)
                            .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app.jar").withExposedPorts(8081)
                            .withEnv(Map.ofEntries(Map.entry("DB_URL", "jdbc:postgresql://postgres:5432/notificacoes"),
                                    Map.entry("DB_USERNAME", "teste"), Map.entry("DB_PASSWORD", senha), Map.entry("RABBITMQ_HOST", "rabbitmq"),
                                    Map.entry("RABBITMQ_USERNAME", "teste"), Map.entry("RABBITMQ_PASSWORD", senha),
                                    Map.entry("MAIL_HOST", "toxiproxy"), Map.entry("RETENCAO_CRON", "-"),
                                    Map.entry("SPRING_RABBITMQ_LISTENER_SIMPLE_CONCURRENCY", "1"),
                                    Map.entry("SPRING_RABBITMQ_LISTENER_SIMPLE_MAX_CONCURRENCY", "1"),
                                    Map.entry("SPRING_RABBITMQ_LISTENER_SIMPLE_PREFETCH", "1"),
                                    Map.entry("SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", "1"),
                                    Map.entry("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "4")))
                            .withCommand("java", "-Xms128m", "-Xmx384m", "-XX:ActiveProcessorCount=1", "-jar", "/app.jar")
                            .waitingFor(Wait.forHttp("/actuator/health/liveness").forPort(8081)).withStartupTimeout(Duration.ofMinutes(3));
                    apps.add(app); iniciar(app);
                }
                aguardar(() -> {
                    assertThat(fila("email").path("consumers").asInt()).isEqualTo(instancias);
                    assertThat(fila("interna").path("consumers").asInt()).isEqualTo(instancias);
                    for (var app : apps) assertThat(metrica(metricas(app), "notificacoes_smtp_circuito_estado", "estado=\"CLOSED\"")).isEqualTo(1);
                });
                gravar(pasta.resolve("recursos.json"), recursosVerificados());
            } catch (Exception | Error falha) { close(); throw falha; }
        }

        Map<String, Object> medir(List<byte[]> carga, int repeticao) throws Exception {
            List<byte[]> aquecimento = new ArrayList<>();
            for (int i = 0; i < 8; i++) aquecimento.add(evento("aquecimento-" + i, "email"));
            publicar(aquecimento, "email");
            aguardar(() -> {
                assertThat(contar("EMAIL", "aquecimento-%")).isEqualTo(8);
                assertThat(total("notificacoes_entregas_total", "canal=\"email\"")).isEqualTo(8);
                for (var app : apps) assertThat(metrica(metricas(app), "notificacoes_eventos_processados_total", "canal=\"email\"")).isPositive();
            });
            double entregasAntes = total("notificacoes_entregas_total", "canal=\"email\"");
            for (var app : apps) app.getDockerClient().pauseContainerCmd(app.getContainerId()).exec();
            long inicio = 0;
            try {
                publicar(carga, "email"); publicar(List.of(evento("interno", "interna")), "interna");
                aguardar(() -> assertThat(fila("email").path("messages").asInt()).isEqualTo(carga.size()));
                gravar(pasta.resolve("fila-inicial.json"), fila("email"));
            } finally {
                inicio = System.nanoTime(); // Inclui o custo de liberar os containers; exclui boot, aquecimento e publicação.
                for (var app : apps) app.getDockerClient().unpauseContainerCmd(app.getContainerId()).exec();
            }
            long internoMs = -1;
            List<Map<String, Object>> amostras = new ArrayList<>();
            long tempoMs;
            double falhas = 0;
            long confirmados = 0;
            while (true) {
                double entregas = 0;
                falhas = 0;
                List<Double> cpu = new ArrayList<>();
                for (var app : apps) {
                    String metricas = metricas(app);
                    entregas += metrica(metricas, "notificacoes_entregas_total", "canal=\"email\"");
                    falhas += metrica(metricas, "notificacoes_falhas_total", "");
                    cpu.add(metrica(metricas, "process_cpu_usage", ""));
                }
                confirmados = contar("EMAIL", "carga-%");
                long internas = contar("INTERNA", "interno");
                tempoMs = (System.nanoTime() - inicio) / 1_000_000;
                if (internoMs < 0 && internas == 1) internoMs = tempoMs;
                amostras.add(Map.of("tempoMs", tempoMs, "confirmados", confirmados, "entregas", entregas - entregasAntes,
                        "pendentes", carga.size() - (entregas - entregasAntes), "falhas", falhas, "cpuPorInstancia", cpu));
                if (confirmados == eventos && entregas - entregasAntes == carga.size()) break;
                assertThat(tempoMs).as("Limite de dez minutos para drenagem").isLessThan(600_000);
                Thread.sleep(250);
            }
            // Tempo já encerrado: estatísticas do broker e inspeção SMTP não entram na duração.
            gravar(pasta.resolve("amostras.json"), amostras);
            assertThat(falhas).isZero(); assertThat(internoMs).isGreaterThanOrEqualTo(0);
            aguardar(() -> {
                for (String canal : List.of("email", "interna", "email.dlq", "interna.dlq", "retry.email.1", "retry.email.2", "retry.email.3"))
                    assertThat(fila(canal).path("messages").asInt()).as(canal).isZero();
            });
            var capturadas = json.readTree(requisicao("GET", url(mail, 8025) + "/api/v1/search?query=subject:MSNOT16-carga-&limit=1000", null, null));
            assertThat(capturadas.get("messages_count").asInt()).isEqualTo(eventos);
            Set<String> destinatarios = new HashSet<>();
            for (var mensagem : capturadas.get("messages")) {
                assertThat(mensagem.get("To").size()).isEqualTo(1);
                assertThat(destinatarios.add(mensagem.at("/To/0/Address").asText())).isTrue();
            }
            for (int i = 0; i < eventos; i++) assertThat(destinatarios).contains("carga-" + i + "@example.test");
            assertThat(contarInternas()).isEqualTo(1);
            double duplicadas = total("notificacoes_entregas_total", "resultado=\"duplicado\"");
            assertThat(duplicadas).isEqualTo(carga.size() - eventos);
            List<Double> porInstancia = new ArrayList<>();
            for (int i = 0; i < apps.size(); i++) {
                String metricas = metricas(apps.get(i));
                Files.writeString(pasta.resolve("instancia-" + i + ".prom"), metricas);
                assertThat(metrica(metricas, "notificacoes_smtp_circuito_estado", "estado=\"CLOSED\"")).isEqualTo(1);
                porInstancia.add(metrica(metricas, "notificacoes_eventos_processados_total", "canal=\"email\""));
            }
            var resultado = new LinkedHashMap<String, Object>();
            resultado.put("repeticao", repeticao); resultado.put("instancias", instancias); resultado.put("drenagemMs", tempoMs);
            resultado.put("vazaoEmailsPorSegundo", eventos / (tempoMs / 1000.0)); resultado.put("falhas", falhas);
            resultado.put("emailsCapturados", eventos); resultado.put("duplicacoesSmtp", 0); resultado.put("marcadoresEmail", confirmados);
            resultado.put("reentregasDescartadas", duplicadas); resultado.put("latenciaInternaObservadaMs", internoMs);
            resultado.put("processadosIncluindoAquecimentoPorInstancia", porInstancia);
            gravar(pasta.resolve("resultado.json"), resultado); gravar(pasta.resolve("captura-smtp.json"), capturadas);
            System.out.println("MS-NOT-16 " + pasta.getFileName() + ": " + tempoMs + "ms, " + resultado.get("vazaoEmailsPorSegundo") + " emails/s");
            return resultado;
        }

        private GenericContainer<?> limitar(GenericContainer<?> c, double cpus, int mib) {
            return c.withNetwork(rede).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withNanoCPUs((long) (cpus * 1_000_000_000L)).withMemory(mib * 1024L * 1024L).withMemorySwap(mib * 1024L * 1024L));
        }
        private GenericContainer<?> iniciar(GenericContainer<?> c) { recursos.add(c); c.start(); return c; }
        private Map<String, Object> ambienteDocker() {
            var info = db.getDockerClient().infoCmd().exec();
            return Map.of("cpus", info.getNCPU(), "memoriaBytes", info.getMemTotal(), "sistema", info.getOperatingSystem(),
                    "versao", db.getDockerClient().versionCmd().exec().getVersion());
        }
        private List<Map<String, Object>> recursosVerificados() {
            List<Map<String, Object>> lista = new ArrayList<>();
            for (var c : recursos) {
                var info = c.getDockerClient().inspectContainerCmd(c.getContainerId()).exec();
                lista.add(Map.of("imagem", c.getDockerImageName(), "imageId", info.getImageId(), "nanoCpus", info.getHostConfig().getNanoCPUs(),
                        "memoriaBytes", info.getHostConfig().getMemory()));
            }
            for (var app : apps) {
                var info = app.getDockerClient().inspectContainerCmd(app.getContainerId()).exec().getHostConfig();
                assertThat(info.getNanoCPUs()).isEqualTo(1_000_000_000L);
                assertThat(info.getMemory()).isEqualTo(768L * 1024 * 1024);
            }
            return lista;
        }
        private long contar(String canal, String padrao) throws Exception {
            try (var c = DriverManager.getConnection("jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/notificacoes", "teste", senha);
                 var s = c.prepareStatement("select count(*) from evento_processado where canal=? and evento_id like ?")) {
                s.setString(1, canal); s.setString(2, padrao); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
            }
        }
        private long contarInternas() throws Exception {
            try (var c = DriverManager.getConnection("jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/notificacoes", "teste", senha);
                 var s = c.createStatement(); var r = s.executeQuery("select count(*) from notificacao_interna")) { r.next(); return r.getLong(1); }
        }
        private void publicar(List<byte[]> carga, String canal) throws Exception {
            var factory = new ConnectionFactory(); factory.setHost(rabbit.getHost()); factory.setPort(rabbit.getMappedPort(5672));
            factory.setUsername("teste"); factory.setPassword(senha);
            try (var c = factory.newConnection(); var channel = c.createChannel()) {
                channel.confirmSelect(); var devolvida = new java.util.concurrent.atomic.AtomicBoolean();
                channel.addReturnListener((ReturnCallback) ignored -> devolvida.set(true));
                for (byte[] evento : carga) channel.basicPublish("notificacoes", canal, true, MessageProperties.PERSISTENT_BASIC, evento);
                channel.waitForConfirmsOrDie(10000); assertThat(devolvida).isFalse();
            }
        }
        private JsonNode fila(String canal) throws Exception { return json.readTree(apiRabbit("GET", "/queues/%2F/notificacoes." + canal, null)); }
        private String apiRabbit(String metodo, String path, String corpo) throws Exception {
            return requisicao(metodo, url(rabbit, 15672) + "/api" + path, corpo,
                    Base64.getEncoder().encodeToString(("teste:" + senha).getBytes(StandardCharsets.UTF_8)));
        }
        private String metricas(GenericContainer<?> app) throws Exception { return requisicao("GET", url(app, 8081) + "/actuator/prometheus", null, null); }
        private double total(String nome, String rotulo) throws Exception { double total = 0; for (var app : apps) total += metrica(metricas(app), nome, rotulo); return total; }
        @Override public void close() {
            for (int i = 0; i < apps.size(); i++) {
                try { Files.writeString(pasta.resolve("instancia-" + i + ".log"), apps.get(i).getLogs()); } catch (Exception ignored) { }
            }
            for (int i = recursos.size() - 1; i >= 0; i--) {
                try {
                    var c = recursos.get(i);
                    if (Boolean.TRUE.equals(c.getDockerClient().inspectContainerCmd(c.getContainerId()).exec().getState().getPaused()))
                        c.getDockerClient().unpauseContainerCmd(c.getContainerId()).exec();
                    c.stop();
                } catch (Exception ignored) { /* Ryuk também acompanha a remoção dos recursos. */ }
            }
            rede.close();
        }
    }

    private String url(GenericContainer<?> c, int port) { return "http://" + c.getHost() + ":" + c.getMappedPort(port); }
    private String requisicao(String metodo, String url, String corpo, String basic) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (basic != null) builder.header("Authorization", "Basic " + basic);
        var response = http.send(builder.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299); return response.body();
    }
    private double metrica(String texto, String nome, String rotulo) {
        return texto.lines().filter(l -> (l.startsWith(nome + "{") || l.startsWith(nome + " ")) && l.contains(rotulo))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }
    private void aguardar(org.awaitility.core.ThrowingRunnable check) { await().ignoreExceptions().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(90)).untilAsserted(check); }
    private void gravar(Path path, Object dados) throws Exception { Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(dados)); }
    private static int parametro(String nome, int padrao, int minimo, int maximo) {
        int valor = Integer.parseInt(System.getProperty(nome, "" + padrao));
        if (valor < minimo || valor > maximo) throw new IllegalArgumentException(nome + " fora do intervalo " + minimo + ".." + maximo);
        return valor;
    }
}
