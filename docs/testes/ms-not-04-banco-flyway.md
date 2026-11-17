# MS-NOT-04 — Evidências de validação

Data local: 11/11/2026. Branch: `feature/ms-not-4`.
Projeto Docker isolado: `ms-not-04-test`. PostgreSQL 17.11, Java 21,
Spring Boot 4.1.1 e Flyway 12.4.0.

## Comandos

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
docker compose -p ms-not-04-test -f compose.yaml -f infra/compose.postgres-it.yaml run --build --rm postgres-it

# Validação da imagem final, sem conflito com a stack principal:
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'
docker compose -p ms-not-04-test up -d --build --scale ms-notificacoes=4 --wait --wait-timeout 240

docker compose -p ms-not-04-test -f compose.yaml -f infra/compose.postgres-it.yaml down
```

As variáveis acima foram definidas somente nos processos de teste. Nenhuma
credencial foi alterada ou registrada neste relatório.

## Resultados

| Verificação | Resultado observado | Status |
|---|---|---|
| Suíte existente no build Java 21 | 32 testes, 0 falhas, 0 erros, 0 ignorados | Passou |
| Suíte `postgres-it` | 9 testes, 0 falhas, 0 erros, 0 ignorados; `BUILD SUCCESS` | Passou |
| Flyway em schema vazio | Schema `it_*` criado e V1 aplicada automaticamente antes da inicialização JPA | Passou |
| Validação/reexecução Flyway | `validate()` passou e segundo `migrate()` executou zero migrations | Passou |
| Índices PostgreSQL | Consulta de `pg_indexes` confirmou índice composto descendente, índice parcial de não lidas e índice de criação | Passou |
| Persistência JPA | UUID gerado, todos os campos recuperados, título Unicode acima de 255 caracteres preservado e instantes com microssegundos | Passou |
| Data de leitura | Inicialmente nula; atualização SQL e recarga JPA preservaram o instante informado | Passou |
| Duplicação de notificação | Mesmo evento/sub rejeitado; destinatários distintos aceitos | Passou |
| Unicidade de sucesso | Mesmo evento/canal rejeitado; outro canal para o mesmo evento aceito | Passou |
| Retenção independente | Exclusão da notificação não apagou o registro de sucesso | Passou |
| Consultas de repositório | Paginação por destinatário, ordenação por criação/UUID e contagem somente das não lidas | Passou |
| Restrições SQL | Rejeitados campos obrigatórios nulos/em branco, tipo de e-mail em notificação interna, canal desconhecido e leitura anterior à criação | Passou |
| Atomicidade suportada pelo schema | Falha simulada antes do commit reverteu notificação e marcador juntos | Passou |
| Minimização | Nenhuma coluna para e-mail, texto de posicionamento ou payload nas tabelas da aplicação | Passou |
| Startup real com quatro instâncias | `public` tinha zero tabelas antes do startup; quatro containers ficaram saudáveis e seus endpoints retornaram `UP` | Passou |
| Histórico do banco usado pela aplicação | Consulta direta retornou apenas `version=1, success=true`; tabelas `notificacao_interna`, `evento_processado` e `flyway_schema_history` criadas | Passou |
| Encerramento | Containers/rede de `ms-not-04-test` removidos; volumes preservados | Passou |

A suíte PostgreSQL foi executada duas vezes. A segunda execução validou a versão
final dos testes e o runner com cache Maven persistente. As violações de
unicidade registradas nos logs são casos negativos esperados e foram verificadas
por assertions; não houve testes com falha.

Os schemas de integração foram isolados e aleatórios; o teste de runtime usou
`public` vazio no mesmo banco exclusivo de testes. Não foram alterados arquivos
do backend nem reiniciados os containers da stack principal `ms-notificacoes`.

## Limites

- O teste H2 de inicialização usa `create-drop` e Flyway desativado somente nele; a prova do SQL e do mapeamento validado é a suíte PostgreSQL.
- Os testes exercitam as restrições e a possibilidade de rollback conjunto; não implementam nem validam coordenação entre consumidores concorrentes (MS-NOT-05).
- Não foram implementados consumidores, envio de e-mails, API de leitura ou expurgo automático nesta task.
- A validação não usou o banco nem a outbox do app-principal.

Procedimento para repetir: [README](../../README.md).
