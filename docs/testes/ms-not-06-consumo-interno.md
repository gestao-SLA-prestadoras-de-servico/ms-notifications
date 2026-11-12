# MS-NOT-06 — Evidências de consumo e persistência interna

Data local: 12/11/2026. Branch: `feature/ms-not-6-consumo-persistencia`.
Ambiente: Java 21, PostgreSQL 17.11 e RabbitMQ 4.1 em Docker.
Projeto principal de testes: `ms-not-06-test`, com portas alternativas à stack
principal e schemas/filas exclusivos para os testes de integração.

## Comandos

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
docker compose -p ms-not-06-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it

# Perfil sem testes RabbitMQ:
docker compose -p ms-not-06-test -f compose.yaml -f infra/compose.postgres-it.yaml run --rm postgres-it

# Repetição da suíte do listener após refinar o teste de canal incorreto:
docker compose -p ms-not-06-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it -B -Pmensageria-it '-Dit.test=ConsumoInternoRabbitMQIT' verify
```

A imagem final foi iniciada no mesmo projeto com `MAIL_SMTP_LOCAL_PORT=21025`
e `MAIL_UI_LOCAL_PORT=28025`, usando `up -d --build --wait`. Um evento fictício de
violação de SLA foi publicado duas vezes na exchange padrão `notificacoes`,
routing key `interna`, pela API de desenvolvimento do RabbitMQ, com
`delivery_mode=2`. O banco foi consultado por `psql`, filtrando o evento com ID
de teste único. Não foram usados eventos do backend.

## Resultados

| Verificação | Resultado observado | Status |
|---|---|---|
| Testes locais Java 21 | 49 testes, 0 falhas/erros/ignorados | Passou |
| Integração completa | 34 testes, 0 falhas/erros/ignorados; `BUILD SUCCESS` | Passou |
| Perfil `postgres-it` | 49 testes locais + 18 PostgreSQL; listener desligado e suítes RabbitMQ excluídas; `BUILD SUCCESS` | Passou |
| Suíte final do listener | 13 testes após refinamento do caso de e-mail válido na fila interna, sem falhas/erros/ignorados | Passou |
| Seis tipos internos | Listener Spring real consumiu atribuição, reatribuição, remoção, reabertura, conclusão e violação de SLA | Passou |
| Dados e destinatários | Cada sub recebido virou uma notificação, com evento/tipo/ID/número/título preservados e um único instante de criação por evento | Passou |
| Leitura inicial | Todas as notificações começaram com `lida_em` nula | Passou |
| Composição | Nove casos de texto: seis templates internos e rejeição dos três tipos de e-mail; título original preservado | Passou |
| Transferência ao substituto | Fixture de reatribuição persistiu somente `sub-tecnico-substituto` | Passou |
| Sub repetido na lista | Lista com sub duplicado produziu somente uma notificação por sub distinto | Passou |
| Republicações concorrentes | 25 publicações do mesmo evento, atendidas por três consumidores reais, produziram três notificações e um marcador | Passou |
| Falha parcial de banco | Trigger de teste falhou no segundo destinatário; nenhuma notificação ou marcador permaneceu; mensagem ficou disponível para reentrega | Passou |
| Recuperação | Após remover a trigger e reiniciar o listener, a mesma entrega produziu o conjunto completo e o marcador | Passou |
| Invalidade/DLQ | Payload sem campos, tipo desconhecido e evento de e-mail válido entregue na fila interna foram à DLQ com razão `rejected`, sem persistência | Passou |
| Isolamento de e-mail | Mensagem roteada para a fila de e-mail permaneceu lá, sem notificação/marcador interno | Passou |
| Imagem final/fila padrão | `notificacoes.interna` tinha consumidor ativo; duas publicações do evento fictício produziram exatamente 3 notificações, 1 marcador, 3 não lidas e 1 instante de criação; saúde `UP` | Passou |
| Regressões MS-NOT-04/05 | Testes existentes de schema, unicidade, concorrência, perda de ACK, rollback e expurgo independente continuaram passando | Passou |
| Smoke de infraestrutura adaptado | `infra/smoke_test.py` desativou o listener somente em `ms-not-03-test`; conferiu topologia, persistência após restart/recriação do broker, DLQs, banco, captura SMTP e quatro instâncias saudáveis | Passou |

Total da suíte completa: **83 testes distintos**, todos aprovados. São 49 locais
e 34 de integração (9 de persistência, 9 de idempotência, 3 de ACK e 13 de consumo
interno). As repetições filtradas não foram somadas novamente a esse total.

Uma tentativa de executar o teste filtrado falhou **antes de executar testes**:
o PowerShell separou o argumento Maven não protegido, resultando em
`Unknown lifecycle phase ".test=ConsumoInternoRabbitMQIT"`. O comando foi corrigido
com aspas em `'-Dit.test=ConsumoInternoRabbitMQIT'` e os 13 testes passaram.
Os erros SQL provocados pela trigger e os NACKs nos logs são casos negativos
esperados, não testes com falha. A trigger, função e sequência de teste foram
removidas no `finally`.

O smoke de infraestrutura foi executado após encerrar `ms-not-06-test`,
reutilizando as portas alternativas em `ms-not-03-test`, com
`CONSUMIDOR_INTERNO_ENABLED=false`:

```powershell
docker compose -p ms-not-03-test up -d --build --wait --wait-timeout 240
python infra/smoke_test.py
docker compose -p ms-not-03-test down
```

As duas stacks descartáveis foram encerradas sem `-v`, preservando seus volumes.

## Limites e cuidados

- Nenhuma migration foi alterada/adicionada; a mensagem é derivada dos dados persistidos pelo getter `@Transient`.
- O produtor real do backend e sua outbox não foram exercitados. Nenhum arquivo do backend foi alterado.
- O listener possui rejeição definitiva de mensagens inválidas e requeue para falhas de processamento. Limite, backoff e classificação completa de retries continuam para MS-NOT-08; falhas persistentes podem gerar reentregas contínuas até lá.
- SMTP, API de leitura e autorização de acesso atual ao chamado não fazem parte desta task.
- Os testes de transferência conferem o evento recebido; não implementam a regra de seleção do substituto no produtor.
- A stack principal não foi reiniciada. Os testes da imagem final foram feitos em `ms-not-06-test`.

Instruções de execução: [README](../../README.md).
