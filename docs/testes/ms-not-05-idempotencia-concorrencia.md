# MS-NOT-05 — Evidências de idempotência e concorrência

Data local: 11/11/2026. Branch: `feature/ms-not-5-indepotencia-concorrencia`.
Ambiente: Java 21, PostgreSQL 17.11 e RabbitMQ 4.1 no projeto Docker
`ms-not-05-test`, com portas distintas da stack principal.

## Execução

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'

# Suíte completa: testes locais, PostgreSQL e RabbitMQ.
docker compose -p ms-not-05-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it

# Confirma que o perfil só de banco não exige as variáveis de RabbitMQ.
docker compose -p ms-not-05-test -f compose.yaml -f infra/compose.postgres-it.yaml run --rm postgres-it

docker compose -p ms-not-05-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml down
```

Nenhuma credencial foi alterada ou registrada nas evidências. Os testes usaram
schemas `it_*` novos e filas RabbitMQ temporárias exclusivas. Não houve alteração
na V1, nos dados da stack principal ou em arquivos do backend.

## Resultados

| Verificação | Observado | Status |
|---|---|---|
| Testes locais/build Java 21 | 37 testes (32 existentes + 5 do adaptador), 0 falhas/erros/ignorados | Passou |
| Integração `mensageria-it` | 21 testes: 9 de persistência, 9 de idempotência e 3 com RabbitMQ; `BUILD SUCCESS` | Passou |
| Compatibilidade `postgres-it` | 37 testes locais + 18 PostgreSQL; suite RabbitMQ excluída pelo perfil; `BUILD SUCCESS` | Passou |
| Sucesso depois da ação | Dentro da ação ainda não havia marcador; após retorno havia sucesso com instante do `Clock` fixo | Passou |
| Reentrega após sucesso | Retornou `JA_PROCESSADO`, sem executar novamente os efeitos | Passou |
| Concorrência | Seis coordenadores independentes/sessões transacionais: uma execução, cinco repetições descartadas, duas notificações e um marcador | Passou |
| Contenção no banco | `pg_locks` mostrou advisory lock não concedido enquanto o primeiro processamento estava bloqueado por latch | Passou |
| Falha parcial interna | Escrita do primeiro destinatário foi revertida junto com a transação; nova tentativa gravou ambos | Passou |
| Falha real no commit | Trigger de teste diferida lançou exceção no commit: ação já havia executado, nenhuma notificação/marcador permaneceu e não houve ACK | Passou |
| Independência de canal | Mesmo evento processado em EMAIL e INTERNA em paralelo | Passou |
| Independência de evento | Dois eventos diferentes entraram simultaneamente nas ações | Passou |
| Retenção independente | Apagar notificações não removeu marcador e não permitiu recriação em reentrega | Passou |
| Transação externa | Rollback externo não desfez o processamento já confirmado em `REQUIRES_NEW` | Passou |
| Timeout de lock | Contendor com `100ms` falhou sem ação/marcador; primeiro processamento concluiu e reentrega posterior foi descartada | Passou |
| ACK depois do commit | Antes do `basicAck` real, consultas em novas transações já enxergavam notificação e sucesso confirmados | Passou |
| Perda de ACK | IOException simulada no ACK após commit; fechamento do canal real provocou reentrega com `redeliver=true`; nova entrega foi confirmada sem duplicação | Passou |
| Falha antes do commit/RabbitMQ | Escrita parcial revertida, sem ACK; fechamento do canal e reentrega permitiram uma execução completa | Passou |
| Contrato e canal inválidos | Rejeitados antes de executar coordenador ou ACK | Passou |
| ACK individual | Confirmada somente a delivery tag atual, com `multiple=false`; falhas propagadas | Passou |

Total da suíte completa: **58 testes distintos**, sem falhas, erros ou testes
ignorados. Os testes negativos exercitaram erros esperados, incluindo violação
de unicidade, timeout de lock e falha diferida no commit. A trigger usada para
simular a falha de commit foi removida no `finally` do teste.

## Limites

- A concorrência foi exercitada por seis instâncias do coordenador em threads e conexões independentes dentro da mesma JVM, com contenção observada no PostgreSQL. Não foi realizado teste entre hosts ou de throughput.
- A falha de ACK foi injetada por um mock no ponto de confirmação; a reentrega ocorreu em conexão/canais RabbitMQ reais, após fechar o canal que mantinha a entrega sem ACK.
- SMTP real não foi exercitado nesta task. A janela de duplicação entre aceitação SMTP e commit do marcador está documentada no README e na implementação da task.
- Os testes chamam o adaptador diretamente com entregas AMQP; listeners automáticos e ações concretas dos canais serão conectados nas MS-NOT-06/MS-NOT-07. Retries/NACK/DLQ ficam para MS-NOT-08.
- O timeout configurado limita a espera pelo lock, não a execução da ação SMTP.

Os containers/rede de teste foram encerrados com `down`, preservando volumes.
A stack principal `ms-notificacoes` permaneceu em execução.

Contrato de uso e instruções para repetir: [README](../../README.md).
