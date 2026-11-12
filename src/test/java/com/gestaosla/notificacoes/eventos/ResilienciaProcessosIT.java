package com.gestaosla.notificacoes.eventos;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
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

/** Testa o JAR empacotado em processos isolados; não utiliza a stack Compose do usuário. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 180)
class ResilienciaProcessosIT {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    private final String senha = UUID.randomUUID().toString();
    private final String run = UUID.randomUUID().toString();
    private final Path evidencias = Path.of("target/ms-not-15", run);
    private final List<GenericContainer<?>> containers = new ArrayList<>();
    private Network network;
    private GenericContainer<?> postgres, rabbit, mail, app;

    @BeforeAll void iniciar() throws Exception {
        Files.createDirectories(evidencias);
        network = Network.newNetwork();
        try {
            postgres = iniciar(new GenericContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withNetwork(network).withNetworkAliases("postgres")
                    .withEnv("POSTGRES_DB", "notificacoes").withEnv("POSTGRES_USER", "teste").withEnv("POSTGRES_PASSWORD", senha)
                    .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)));
            rabbit = iniciar(new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                    .withNetwork(network).withNetworkAliases("rabbitmq")
                    .withEnv("RABBITMQ_DEFAULT_USER", "teste").withEnv("RABBITMQ_DEFAULT_PASS", senha)
                    .withExposedPorts(5672, 15672).waitingFor(Wait.forHttp("/api/overview").forPort(15672).withBasicCredentials("teste", senha)));
            String topologia = Files.readString(Path.of("docs/topologia.json"));
            aguardar(() -> apiRabbit("POST", "/definitions", topologia));
            mail = iniciar(new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27"))
                    .withNetwork(network).withNetworkAliases("mailpit").withExposedPorts(8025)
                    .waitingFor(Wait.forHttp("/readyz").forPort(8025)));
            Path jar;
            try (var arquivos = Files.list(Path.of("target"))) {
                jar = arquivos.filter(p -> p.getFileName().toString().matches("ms-notificacoes-.*\\.jar")).findFirst().orElseThrow();
            }
            app = iniciar(new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre-alpine"))
                    .withNetwork(network).withCopyFileToContainer(MountableFile.forHostPath(jar), "/app.jar")
                    .withEnv(ambiente("postgres", "rabbitmq", "mailpit", 5432, 5672))
                    .withCommand("java", "-jar", "/app.jar").withExposedPorts(8081)
                    .waitingFor(Wait.forHttp("/actuator/health/liveness").forPort(8081)).withStartupTimeout(Duration.ofMinutes(2)));
            saudavel(); snapshot("00-inicial");
        } catch (Exception | Error falha) { encerrar(); throw falha; }
    }

    private GenericContainer<?> iniciar(GenericContainer<?> c) { containers.add(c); c.start(); return c; }
    private int porta(GenericContainer<?> c, int interna) {
        // Docker pode atribuir outra porta aleatória após stop/start do mesmo container.
        var bindings = c.getDockerClient().inspectContainerCmd(c.getContainerId()).exec().getNetworkSettings().getPorts()
                .getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(interna));
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }
    private Map<String, String> ambiente(String db, String broker, String smtp, int dbPort, int amqpPort) {
        return Map.ofEntries(Map.entry("DB_URL", "jdbc:postgresql://" + db + ":" + dbPort + "/notificacoes?connectTimeout=1&socketTimeout=2"),
                Map.entry("DB_USERNAME", "teste"), Map.entry("DB_PASSWORD", senha), Map.entry("RABBITMQ_HOST", broker),
                Map.entry("RABBITMQ_PORT", "" + amqpPort), Map.entry("RABBITMQ_USERNAME", "teste"), Map.entry("RABBITMQ_PASSWORD", senha),
                Map.entry("MAIL_HOST", smtp), Map.entry("MAIL_CONNECTION_TIMEOUT_MS", "500"), Map.entry("MAIL_READ_TIMEOUT_MS", "500"),
                Map.entry("SMTP_CIRCUIT_OPEN_WAIT", "1s"), Map.entry("SMTP_PROBE_INTERVAL", "500ms"), Map.entry("RETENCAO_CRON", "-"),
                Map.entry("SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", "1"), Map.entry("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "1000"),
                Map.entry("SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT", "500"));
    }

    @Test @Order(1) void interrupcaoSmtpAcumulaEmailPreservaInternasERecuperaSemReinicio() throws Exception {
        String id = app.getContainerId();
        long reinicios = app.getDockerClient().inspectContainerCmd(id).exec().getRestartCount();
        snapshot("01-smtp-antes");
        mail.getDockerClient().pauseContainerCmd(mail.getContainerId()).exec();
        try {
            for (int i = 0; i < 6; i++) publicar("email", "smtp-" + i);
            publicar("interna", "smtp-interno");
            aguardar(() -> {
                assertThat(metrica("notificacoes_smtp_circuito_estado", "estado=\"OPEN\"")).isEqualTo(1);
                assertThat(contar("smtp-interno")).isEqualTo(1);
                assertThat(fila("email").get("messages").asInt()).isEqualTo(6);
                assertThat(fila("email").get("consumers").asInt()).isZero();
                assertThat(metrica("notificacoes_falhas_total", "categoria=\"indisponibilidade_smtp\"")).isGreaterThanOrEqualTo(1);
                assertThat(get(urlApp() + "/actuator/health/liveness")).contains("UP");
            });
            snapshot("02-smtp-indisponivel");
        } finally { mail.getDockerClient().unpauseContainerCmd(mail.getContainerId()).exec(); }
        aguardar(() -> {
            assertThat(metrica("notificacoes_smtp_circuito_estado", "estado=\"CLOSED\"")).isEqualTo(1);
            assertThat(fila("email").get("messages").asInt()).isZero();
            assertThat(contarPrefixo("smtp-", "EMAIL")).isEqualTo(6);
            assertThat(json.readTree(get("http://" + mail.getHost() + ":" + porta(mail, 8025)
                    + "/api/v1/search?query=subject:" + run)).get("messages_count").asInt()).isEqualTo(6);
        });
        assertThat(app.getContainerId()).isEqualTo(id);
        assertThat(app.getDockerClient().inspectContainerCmd(id).exec().getRestartCount()).isEqualTo(reinicios);
        snapshot("03-smtp-recuperado");
    }

    @Test @Order(2) void bancoIndisponivelEsgotaRetriesSemSucessoEPermiteReprocessarDlq() throws Exception {
        snapshot("04-banco-antes");
        postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).withTimeout(0).exec();
        try {
            publicar("interna", "banco");
            aguardar(() -> assertThat(metrica("notificacoes_falhas_total", "categoria=\"temporaria\"")).isGreaterThanOrEqualTo(1));
            snapshot("05a-banco-retry");
            aguardar(() -> assertThat(fila("interna.dlq").get("messages").asInt()).isEqualTo(1));
            assertThat(metrica("notificacoes_falhas_total", "categoria=\"temporaria\"")).isGreaterThanOrEqualTo(4);
            snapshot("05-banco-dlq");
        } finally { postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec(); }
        aguardar(() -> assertThat(contar("banco")).isZero());
        try (var connection = conexao(); var channel = connection.createChannel()) {
            var dead = channel.basicGet("notificacoes.interna.dlq", false);
            assertThat(dead).isNotNull();
            assertThat(dead.getProps().getHeaders().get(ProcessadorEntrega.TENTATIVA)).isEqualTo(4);
            assertThat(dead.getProps().getHeaders().get(ProcessadorEntrega.CATEGORIA).toString()).isEqualTo("TEMPORARIA");
            // Reprocessamento operacional: remove os headers de tentativa, preserva corpo e eventoId.
            channel.confirmSelect();
            channel.basicPublish("notificacoes", "interna", true, MessageProperties.PERSISTENT_BASIC, dead.getBody());
            channel.waitForConfirmsOrDie(5000);
            channel.basicAck(dead.getEnvelope().getDeliveryTag(), false);
        }
        aguardar(() -> assertThat(contar("banco")).isEqualTo(1));
        snapshot("06-banco-recuperado");
    }

    @Test @Order(3) void mensagensConfirmadasSobrevivemReinicioDoServicoEDoBroker() throws Exception {
        snapshot("07-reinicio-antes");
        matarApp();
        for (int i = 0; i < 5; i++) publicar("interna", "reinicio-" + i);
        aguardar(() -> assertThat(fila("interna").get("messages").asInt()).isEqualTo(5));
        snapshot("08-servico-parado");
        String brokerId = rabbit.getContainerId();
        rabbit.getDockerClient().restartContainerCmd(brokerId).withTimeout(0).exec();
        aguardar(() -> assertThat(fila("interna").get("messages").asInt()).isEqualTo(5));
        assertThat(rabbit.getContainerId()).isEqualTo(brokerId);
        snapshot("09-broker-reiniciado");
        iniciarApp();
        aguardar(() -> {
            assertThat(contarPrefixo("reinicio-", "INTERNA")).isEqualTo(5);
            assertThat(fila("interna").get("messages").asInt()).isZero();
        });
        snapshot("10-reinicio-recuperado");
    }

    @Test @Order(4) void quedaRealDaJvmDepoisDoCommitAntesDoAckNaoDuplicaInterna() throws Exception {
        matarApp();
        publicar("interna", "commit-sem-ack");
        Path argumentos = evidencias.resolve("java.args");
        // Argfile evita o limite de tamanho de linha de comando do Windows.
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(argumentos, "-cp\n\"" + classpath.replace("\\", "\\\\") + "\"\n" + ProcessoAntesDoAck.class.getName());
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + argumentos.toAbsolutePath());
        builder.environment().putAll(ambiente(postgres.getHost(), rabbit.getHost(), mail.getHost(), porta(postgres, 5432), porta(rabbit, 5672)));
        builder.redirectErrorStream(true).redirectOutput(evidencias.resolve("processo-interrompido.log").toFile());
        Process processo = builder.start();
        try {
            assertThat(processo.waitFor(90, TimeUnit.SECONDS)).isTrue();
            assertThat(processo.exitValue()).isEqualTo(37);
        } finally { if (processo.isAlive()) processo.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
        assertThat(contar("commit-sem-ack")).isEqualTo(1);
        try (var connection = conexao(); var channel = connection.createChannel()) {
            var reentrega = channel.basicGet("notificacoes.interna", false);
            assertThat(reentrega).isNotNull();
            assertThat(reentrega.getEnvelope().isRedeliver()).isTrue();
            channel.basicNack(reentrega.getEnvelope().getDeliveryTag(), false, true);
        }
        snapshot("11-commit-sem-ack");
        iniciarApp();
        aguardar(() -> {
            assertThat(fila("interna").get("messages").asInt()).isZero();
            assertThat(contar("commit-sem-ack")).isEqualTo(1);
            assertThat(metrica("notificacoes_entregas_total", "resultado=\"duplicado\"")).isEqualTo(1);
        });
        snapshot("12-reentrega-deduplicada");
    }

    private void matarApp() { app.getDockerClient().killContainerCmd(app.getContainerId()).withSignal("KILL").exec(); }
    private void iniciarApp() { app.getDockerClient().startContainerCmd(app.getContainerId()).exec(); saudavel(); }
    private void saudavel() { aguardar(() -> assertThat(get(urlApp() + "/actuator/health/liveness")).contains("UP")); }
    private void aguardar(org.awaitility.core.ThrowingRunnable check) { await().ignoreExceptions().pollInterval(Duration.ofMillis(500)).atMost(Duration.ofSeconds(90)).untilAsserted(check); }
    private Connection conexao() throws Exception {
        var factory = new ConnectionFactory(); factory.setHost(rabbit.getHost()); factory.setPort(porta(rabbit, 5672));
        factory.setUsername("teste"); factory.setPassword(senha); factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection();
    }
    private void publicar(String canal, String id) throws Exception {
        String fixture = canal.equals("email") ? "resolucao" : "atribuicao";
        var event = (ObjectNode) json.readTree(Files.readString(Path.of("docs/contratos/exemplos/validos/" + fixture + ".json")));
        event.put("eventoId", run + "-" + id); event.put("titulo", run);
        event.putArray("destinatarios").add(canal.equals("email") ? "teste@example.test" : "sub-teste");
        try (var connection = conexao(); var channel = connection.createChannel()) {
            channel.confirmSelect();
            var devolvida = new java.util.concurrent.atomic.AtomicBoolean();
            channel.addReturnListener((ReturnCallback) returned -> devolvida.set(true));
            channel.basicPublish("notificacoes", canal, true, MessageProperties.PERSISTENT_BASIC, json.writeValueAsBytes(event));
            channel.waitForConfirmsOrDie(5000); assertThat(devolvida).isFalse();
        }
    }
    private java.sql.Connection banco() throws Exception { return DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":" + porta(postgres, 5432) + "/notificacoes?connectTimeout=2&socketTimeout=3", "teste", senha); }
    private long contar(String id) throws Exception {
        try (var c = banco(); var s = c.prepareStatement("select count(*) from notificacao_interna where evento_id=?")) {
            s.setString(1, run + "-" + id); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    private long contarPrefixo(String prefixo, String canal) throws Exception {
        try (var c = banco(); var s = c.prepareStatement("select count(*) from evento_processado where evento_id like ? and canal=?")) {
            s.setString(1, run + "-" + prefixo + "%"); s.setString(2, canal); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    private String urlApp() { return "http://" + app.getHost() + ":" + porta(app, 8081); }
    private String get(String url) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); return response.body();
    }
    private String apiRabbit(String metodo, String path, String body) throws Exception {
        String auth = Base64.getEncoder().encodeToString(("teste:" + senha).getBytes(StandardCharsets.UTF_8));
        var response = http.send(HttpRequest.newBuilder(URI.create("http://" + rabbit.getHost() + ":" + porta(rabbit, 15672) + "/api" + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Basic " + auth).header("Content-Type", "application/json")
                .method(metodo, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299); return response.body();
    }
    private JsonNode fila(String nome) throws Exception { return json.readTree(apiRabbit("GET", "/queues/%2F/notificacoes." + nome, null)); }
    private double metrica(String nome, String rotulo) throws Exception {
        return get(urlApp() + "/actuator/prometheus").lines().filter(l -> l.startsWith(nome + "{") && l.contains(rotulo))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }
    private void snapshot(String fase) throws Exception {
        var registro = json.createObjectNode(); registro.put("fase", fase); registro.put("instante", Instant.now().toString());
        try (var c = banco(); var s = c.createStatement(); var r = s.executeQuery(
                "select (select count(*) from notificacao_interna), (select count(*) from evento_processado)")) {
            r.next(); registro.put("notificacoesPersistidas", r.getLong(1)); registro.put("eventosConfirmados", r.getLong(2));
        } catch (Exception indisponivel) { registro.put("bancoDisponivel", false); }
        for (String canal : List.of("email", "interna", "email.dlq", "interna.dlq",
                "retry.email.1", "retry.email.2", "retry.email.3", "retry.interna.1", "retry.interna.2", "retry.interna.3"))
            registro.set(canal, fila(canal));
        Files.writeString(evidencias.resolve(fase + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(registro));
        try { Files.writeString(evidencias.resolve(fase + ".prom"), get(urlApp() + "/actuator/prometheus")); }
        catch (Exception indisponivel) { Files.writeString(evidencias.resolve(fase + ".prom"), "# processo indisponivel nesta fase\n"); }
    }
    @AfterAll void encerrar() {
        if (app != null && app.getContainerId() != null) {
            try { Files.writeString(evidencias.resolve("servico.log"), app.getLogs()); } catch (Exception ignored) { }
        }
        for (int i = containers.size() - 1; i >= 0; i--) containers.get(i).stop();
        containers.clear(); if (network != null) network.close();
    }
}
