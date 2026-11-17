# MS-NOT-14 — Testes funcionais, de integração e segurança

O perfil Maven `completo-it` executa os testes locais e todos os `*IT` com PostgreSQL, RabbitMQ, Mailpit e Keycloak reais. O listener JUnit `InfraestruturaIT` inicia a infraestrutura antes dos contextos Spring, define suas propriedades e encerra os containers ao finalizar a sessão. O Testcontainers/Ryuk acompanha os recursos temporários. Não há reutilização de containers nem volumes persistentes de aplicação.

| Critério | Cobertura automatizada |
|---|---|
| Tipos de evento, campos inválidos e múltiplos destinatários | LeitorEventoNotificacaoTest, ConsumoInternoRabbitMQIT |
| Reentrega, ACK após commit e concorrência sem duplicação | ConfirmacaoRabbitMQIT, IdempotenciaPostgresIT, ConsumoInternoRabbitMQIT |
| SMTP, conteúdo, escape HTML e destinatários individuais | ConsumoEmailMailpitIT |
| Retentativas, falhas permanentes e DLQ | RetryRabbitMQIT |
| Paginação, contagem, leitura repetida e isolamento | NotificacoesApiPostgresIT, FluxoSeguroIT |
| Assinatura, emissor, audiência, expiração, perfis e CORS | SegurancaJwtTest, KeycloakJwtIT |
| Expurgo antes, exatamente em e após 90 dias | RetencaoPostgresIT |
| Transferência para substituto sem aviso ao inativado | ConsumoInternoRabbitMQIT, usando o exemplo REATRIBUICAO do contrato |

`FluxoSeguroIT` publica no RabbitMQ, aguarda persistência no PostgreSQL e acessa os controllers e filtros reais com tokens emitidos pelo Keycloak, sem substituir o decoder JWT. Informar outro `sub` na requisição não altera a identidade, e outro usuário não pode marcar a notificação como lida. A matriz mais ampla de dados e paginação permanece no teste específico da API.

A seleção de destinatários na inativação é responsabilidade do produtor: a suíte comprova o consumo do evento fornecido, sem alegar que executou o fluxo de inativação do backend. A fixture existente contém somente o substituto, e o teste verifica a ausência de notificações REMOCAO ou destinadas ao inativado.

Os perfis Compose anteriores continuam disponíveis para diagnóstico com infraestrutura externa. `ConfiguracaoIT` aceita propriedades da JVM e, na ausência delas, as variáveis dos runners existentes. Somente `completo-it` cria infraestrutura automaticamente; não combine os perfis.

Referência das estratégias de prontidão: [Testcontainers](https://java.testcontainers.org/features/startup_and_waits/).
