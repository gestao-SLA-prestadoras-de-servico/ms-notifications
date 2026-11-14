# Testes — MS-NOT-14

## Execução

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
.\mvnw.cmd -B -Pcompleto-it verify
```

Use o diretório do seu JDK 21. O perfil usa Testcontainers 2.0.5 (gerenciado pelo Spring Boot), PostgreSQL 17, RabbitMQ 4.1, Mailpit 1.27 e Keycloak 26.4. Todas as portas são atribuídas dinamicamente e as senhas são geradas por execução. Nenhuma credencial ou volume da stack de desenvolvimento é necessário.

## Evidências e limites

A execução final no Java 21.0.12.1 terminou com **BUILD SUCCESS**, 161 testes locais e 84 testes de integração, todos sem falhas, erros ou testes ignorados. O novo FluxoSeguroIT passou com PostgreSQL, RabbitMQ e JWT real do Keycloak. A suíte completa levou 3 minutos e 28 segundos neste ambiente.

A primeira execução validou a criação automática das quatro dependências e a regressão com 161 testes locais e 83 testes de integração sem falhas ou testes ignorados. Essa execução compilou para Java 21, mas utilizou a JVM 25 configurada no JAVA_HOME do host; a validação final foi executada explicitamente com JDK 21 e inclui o novo FluxoSeguroIT.

A tentativa inicial sob sandbox não alcançou o Maven Central (Permission denied ao baixar o parent POM). A execução com acesso de rede e Docker resolveu as dependências e executou a suíte real, sem substituir infraestrutura por mocks. Os testes locais permanecem independentes de Docker. Relatórios Maven ficam em `target/surefire-reports` e `target/failsafe-reports`; logs desta validação ficam em `target/ms-not-14-final.log`.

O teste funcional usa o listener real e a API por MockMvc com a cadeia real de segurança. A validação de assinatura usa JWKS do Keycloak temporário. PostgreSQL aplica as migrations Flyway em schemas exclusivos, e o SMTP real é capturado pelo Mailpit. Os testes existentes da API cobrem paginação, contagem e leitura concorrente; os testes de consumo cobrem reentrega, concorrência, destinatários e payloads inválidos. A retenção verifica a fronteira exata de 90 dias.

Os cenários de transferência verificam o contrato do evento do produtor; não executam a inativação no backend. Esta task também não faz varredura de vulnerabilidades de dependências nem implementa o workflow de CI da MS-NOT-17.
