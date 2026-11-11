# ms-notificacoes

Serviço independente de notificações do FlowSLA. O app-principal publica eventos no RabbitMQ; futuramente este serviço consumirá as filas de e-mail e de notificações internas, usando seu próprio banco PostgreSQL. A API será consumida pelo frontend. Os serviços não compartilham banco nem consultam a API um do outro para compor notificações.

## Requisitos e execução

- Java 21; o Maven Wrapper baixa o Maven quando necessário.
- PostgreSQL acessível com banco e usuário próprios para o serviço.
- Defina as variáveis de ambiente usando [.env.example](.env.example) como referência. O arquivo é apenas um exemplo: o Spring Boot não carrega `.env` automaticamente.

Execute `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). Use `./mvnw test` para compilar e executar os testes. A porta padrão é 8081 e o endpoint de saúde é `/actuator/health`; sua resposta reflete a disponibilidade das dependências monitoradas.

A base contém os pacotes `api`, `eventos`, `persistencia`, `integracoes` e `config`. Por enquanto, somente a saúde está acessível: os demais caminhos são negados até a implementação da autenticação e autorização na MS-NOT-10. Não há consumidores, API de notificações, migrations ou criação de filas nesta etapa; esses itens pertencem às tasks posteriores em [tasks_ms_not.md](docs/tasks/tasks_ms_not.md).

Não versione credenciais. O RabbitMQ e o SMTP são configurados por variáveis de ambiente; a topologia planejada está em [topologia.json](docs/topologia.json) e o fluxo em [flowSLA.md](docs/flowSLA.md).
