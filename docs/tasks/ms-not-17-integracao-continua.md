# MS-NOT-17 — Integração contínua

O workflow `.github/workflows/ci.yml` executa em push, pull request e acionamento manual no GitHub Actions. Usa Java 21, cache Maven e `completo-it`, que provisiona PostgreSQL, RabbitMQ, Mailpit e Keycloak temporários com Testcontainers. Falhas interrompem o job; relatórios Surefire/Failsafe são enviados mesmo após falha. Em caso de sucesso, constrói a imagem Docker localmente no runner, sem publicá-la.

O runner hospedado precisa acessar Maven Central e os registries das imagens. Não são necessários segredos de produção. O token padrão tem somente permissão de leitura do conteúdo. Resiliência e escala permanecem em perfis específicos devido ao tempo de execução.

Referência: [GitHub Actions: Java com Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven). Evidências: [testes](../testes/ms-not-17-integracao-continua.md).
