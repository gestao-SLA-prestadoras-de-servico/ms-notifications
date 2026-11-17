# MS-NOT-12 — Evidências de retenção e minimização

Data: 12/11/2026. Branch: `feature/ms-not-12-reter-minimizar`. Stack isolada: `ms-not-12-test`, PostgreSQL 17, RabbitMQ 4.1 e Java 21.

## Execução

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'
docker compose -p ms-not-12-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it
docker compose -p ms-not-12-test down
```

Na execução realizada, os runners usaram modo offline após copiar o cache Maven da MS-NOT-11 por montagem somente leitura para um volume exclusivo da MS-NOT-12. Nenhum volume foi removido.

## Evidências

| Cenário | Resultado |
|---|---|
| Fronteira/lotes e marcador | Cinco notificações anteriores ao limite apagadas em lotes de dois; exatamente 90 dias e mais recente preservadas; marcador preservado; segunda execução idempotente |
| Concorrência | Linha bloqueada ignorada, outra removida; após liberação, próxima execução remove a pendente |
| Agendamento | Cron de teste disparou automaticamente o job e removeu uma notificação expirada em schema isolado |
| Logs AMQP | Exceção e payload com dados fictícios não aparecem no log; falha fatal continua rejeitada e `x-death` conserva descarte padrão |
| DLQs | `rabbitmqctl list_queues name arguments` mostrou `x-message-ttl=604800000` em `notificacoes.email.dlq` e `notificacoes.interna.dlq` |
| Regressão final | 139 testes locais e 75 integrações, sem falhas/erros/ignorados; `BUILD SUCCESS` |
| Factory direct | Contexto inicializado com `spring.rabbitmq.listener.type=direct`; `BUILD SUCCESS` |
| Busca por dados fictícios no log | `cliente@example.com` e `texto-privado` não apareceram no log da regressão |

Os testes de banco usam schema aleatório, Clock fixo, Flyway e PostgreSQL reais. O agendamento usa cron por segundo apenas no teste, sem alterar o padrão diário de produção. O teste de linha bloqueada usa duas transações concorrentes e `FOR UPDATE`; não envolve dados da aplicação principal.

Revisão de minimização: tabelas são `notificacao_interna` e `evento_processado`, sem colunas de e-mail/posicionamento; os logs explícitos de processamento registram canal/tentativa/categoria. Os DTOs de evento e conteúdo de email têm `toString` sanitizado. Eventos permanecem temporariamente no broker, inclusive em filas de retry/DLQ; TTL de sete dias aplica-se às DLQs, não ao banco nem à fila de trabalho.

O framework AMQP podia incluir o corpo na exceção e em WARN durante encerramento. O tratador e o nível de log do container foram ajustados. Isso suprime também outros WARN do container; logs de debug de protocolos/SQL de parâmetros não devem ser habilitados com dados reais.

Logs locais ignorados pelo Git: `target/ms-not-12-postgres-it.log`, `target/ms-not-12-final.log`, `target/ms-not-12-validado.log`, `target/ms-not-12-regressao-final.log` e `target/ms-not-12-direct.log`.

A regressão final soma **214 testes distintos** (139 locais e 75 de integração). O teste de inicialização direct repete um teste local com outra configuração e não é somado novamente.

A stack `ms-not-12-test` foi encerrada após a validação, com volumes preservados. Os quatro containers da stack principal estavam saudáveis na conferência final e não foram atualizados por esses testes. Não houve commit, push ou mudança de branch.

## Limites

Não houve alteração no backend. Não foi necessário modificar o schema, a topologia ou os marcadores de idempotência. O teste verifica a configuração do TTL, sem aguardar sete dias. A política de retenção dos marcadores não foi alterada.
