package com.gestaosla.notificacoes.suporte;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Uma infraestrutura por sessão Failsafe, antes da criação de qualquer contexto Spring. */
public final class InfraestruturaIT implements LauncherSessionListener {
    private final List<GenericContainer<?>> containers = new ArrayList<>();

    @Override public void launcherSessionOpened(LauncherSession session) {
        if (!Boolean.getBoolean("notificacoes.testcontainers")) return;
        String senha = UUID.randomUUID().toString();
        try {
            var postgres = iniciar(new GenericContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withEnv("POSTGRES_DB", "notificacoes_test").withEnv("POSTGRES_USER", "teste")
                    .withEnv("POSTGRES_PASSWORD", senha).withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)));
            propriedade("IT_DB_URL", "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/notificacoes_test");
            propriedade("IT_DB_USERNAME", "teste"); propriedade("IT_DB_PASSWORD", senha);
            var rabbit = iniciar(new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "teste").withEnv("RABBITMQ_DEFAULT_PASS", senha)
                    .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1)));
            propriedade("IT_RABBITMQ_HOST", rabbit.getHost());
            propriedade("IT_RABBITMQ_PORT", rabbit.getMappedPort(5672).toString());
            propriedade("IT_RABBITMQ_USERNAME", "teste"); propriedade("IT_RABBITMQ_PASSWORD", senha);
            propriedade("IT_RABBITMQ_VHOST", "/");
            var mail = iniciar(new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27"))
                    .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/readyz").forPort(8025)));
            propriedade("IT_MAIL_HOST", mail.getHost()); propriedade("IT_MAIL_PORT", mail.getMappedPort(1025).toString());
            propriedade("IT_MAILPIT_URL", "http://" + mail.getHost() + ":" + mail.getMappedPort(8025));
            var keycloak = iniciar(new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.4"))
                    .withEnv("IT_KEYCLOAK_PASSWORD", senha)
                    .withCopyFileToContainer(MountableFile.forHostPath(Path.of("infra/keycloak/flowsla-ms-not-test-realm.json")),
                            "/opt/keycloak/data/import/realm.json")
                    .withCommand("start-dev", "--import-realm").withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/realms/flowsla-ms-not-test/.well-known/openid-configuration").forPort(8080))
                    .withStartupTimeout(Duration.ofMinutes(3)));
            propriedade("IT_KEYCLOAK_URL", "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080));
            propriedade("IT_KEYCLOAK_PASSWORD", senha);
        } catch (RuntimeException | Error falha) {
            encerrar();
            throw falha; // Docker/infraestrutura indisponível deve falhar, nunca ignorar testes.
        }
    }

    private GenericContainer<?> iniciar(GenericContainer<?> container) {
        containers.add(container);
        container.start();
        return container;
    }
    private static void propriedade(String nome, String valor) { System.setProperty(nome, valor); }
    @Override public void launcherSessionClosed(LauncherSession session) { encerrar(); }
    private void encerrar() {
        for (int i = containers.size() - 1; i >= 0; i--) containers.get(i).stop();
        containers.clear();
    }
}
