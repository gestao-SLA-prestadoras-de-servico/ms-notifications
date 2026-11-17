# Integração com o backend (outbox → RabbitMQ → ms-notificacoes)

> Executado em 17/11/2026. Testes automatizados com Testcontainers nos dois repositórios. Ponta a ponta no stack de dev do backend (`infra-*`, rede `flowsla-dev`), com o ms em container (`docker run --network flowsla-dev`) usando o RabbitMQ, o Postgres (banco `notificacoes`), o Mailpit e o Keycloak do backend.
>
> Referência: `backend/docs/tasks/outbox/CONVENÇÕES_RABBITMQ.md` e os schemas de `backend/contratos/notificacoes/`.

## Situação encontrada

Antes deste trabalho, **o ms não conseguia processar nenhuma mensagem real do backend**: todas iriam para a DLQ. O ms foi feito sobre um rascunho de contrato próprio e nunca tinha sido testado contra o produtor.

| # | Divergência | Correção |
|---|---|---|
| B1 | `chamado.numero`: o backend manda inteiro; o ms exigia string. Quebrava todos os tipos. | `ChamadoEvento.numero` passou a `long`; o ms exibe `#1042` (`exibicao()`). |
| B2 | Tipos: o backend usa `VIOLACAO` e `POSICIONAMENTO`; o ms esperava `VIOLACAO_SLA`, `PRIMEIRO_POSICIONAMENTO` e um `REATRIBUICAO` que o backend não envia. | `TipoEvento` alinhado. Migração `V2__alinhar_contrato_backend.sql`: converte linhas antigas e troca o CHECK. |
| B3 | `relogio` e `encerramentoPrevistoEm` eram recusados como campos desconhecidos. | Campos no record. O sino cita o relógio violado (coluna `relogio`, V2); o aviso de encerramento mostra a data no horário de Brasília. |
| B4 | O retry do ms usa a exchange `notificacoes.retry`, 6 filas e 3 policies que não existiam no broker do backend. Uma falha temporária virava loop de requeue. | Objetos incluídos em `backend/infra/rabbitmq/topologia.json`, idênticos aos do `docs/topologia.json` do ms. Documentados nas seções 3 e 5 das convenções. |
| R8 | Regra 8 das convenções: o ms não validava com os JSON Schemas (uuid, date-time, enum do relógio, `maxItems: 1` do e-mail). | Cópia dos 8 schemas em `src/main/resources/contratos/notificacoes/`, validados com networknt 3.0.8 e `formatAssertionsEnabled(true)`. Um teste falha se a cópia divergir do backend. |

As demais regras da seção 5 das convenções já eram cumpridas: não declara topologia, um consumidor por fila, idempotência gravada depois do sucesso, ack depois do commit, classificação 4xx/5xx, pausa no circuito aberto, não consulta o backend, não depende de ordem e ignora headers desconhecidos.

A regra 6 tinha um desvio. O ms manda para a DLQ republicando uma cópia em `notificacoes.dlx` e dando ack, em vez de `reject`. Com a topologia do backend a mensagem chega à DLQ certa, e o desvio foi aceito e documentado na regra 6.

## Testes automatizados

| # | O que | Como | Resultado |
|---|---|---|---|
| 1 | Suíte do backend | `TZ=UTC ./mvnw test` | 526 testes, 0 falhas (eram 523; +3 de `TopologiaRabbitTest`) |
| 2 | Retry do ms na topologia do backend | `TopologiaRabbitTest.shouldReturnRetriedMessageToChannelQueueAfterDelay` (`email.1`, `email.3`, `interna.2`) | a mensagem volta à fila do canal depois do TTL ✅ |
| 3 | Unitários do ms | `./mvnw test` | 175 testes, 0 falhas (eram 161) |
| 4 | Integração do ms | `./mvnw -B -Pcompleto-it verify` | 84 testes, 0 falhas |
| 5 | Contrato | `LeitorEventoNotificacaoTest`: os 8 exemplos válidos no formato do backend, 25 inválidos, chaves em qualquer ordem, cópia dos schemas igual à do backend | ✅ |
| 6 | Quebra: `format` desligado | `formatAssertionsEnabled(false)` | 6 falhas no `LeitorEventoNotificacaoTest` ✅ (revertido) |
| 7 | Migração V2 sobre dados antigos | `PersistenciaPostgresIT.v2ConverteTiposDoContratoAntigoSemPerderNotificacoes` | `VIOLACAO_SLA` → `VIOLACAO`, `REATRIBUICAO` → `ATRIBUICAO`; nenhuma linha perdida; `relogio` só em VIOLACAO ✅ |

Testes do ms ajustados ao contrato:
- ids e subs passaram a ser uuid (helper `suporte/IdsTeste`);
- o caso de vários e-mails no mesmo evento virou caso de DLQ, porque o contrato tem `maxItems: 1`;
- a reatribuição passou a ser ATRIBUICAO + REMOCAO.

Os perfis `resiliencia-it` e `escala-it` foram ajustados e compilam, mas **não foram executados**.

## Ponta a ponta no stack de dev

Os eventos foram gravados direto em `outbox_eventos` pelo psql, como no passo 7C do `plano-testes-manuais.md` do backend. O `PublicadorOutbox` real os publicou.

| # | Cenário | Esperado | Obtido | Status |
|---|---|---|---|---|
| 1 | Topologia nova no broker de dev (`rabbitmq-init` + restart do RabbitMQ) | exchange, 6 filas quorum e 3 policies de retry; mensagens existentes preservadas | conforme; as 9 mensagens que estavam paradas nas filas continuaram lá | ✅ |
| 2 | Subida do ms no banco `notificacoes` do backend | Flyway aplica V1 e V2 | `now at version v2` | ✅ |
| 3 | 9 mensagens reais antigas do backend (roteiro 7K, publicadas em 16/11 e nunca consumidas) | internas viram sino; inválidas vão para a DLQ | 6 notificações no sino. A linha de teste manual `d45a04f0` (payload só com `eventoId`) foi para `notificacoes.interna.dlq`. **As 2 RESOLUCAO foram para a DLQ**: ver achado 1 | ⚠️ |
| 4 | Um evento de cada um dos 8 tipos pela outbox (`r1`) | 5 tipos no sino, 3 e-mails | 6 linhas no sino (a VIOLACAO tem 2 destinatários), `#9002`, `relogio=RESOLUCAO`. 3 e-mails no Mailpit; o aviso diz "20/11/2026 às 12:30 (horário de Brasília)" para `2026-11-20T15:30:00Z`; o posicionamento traz o texto. DLQs sem mudança | ✅ |
| 5 | Duplicata: `publicado_em = null` em dois eventos já processados | republicados e descartados | republicados; o sino continuou com 6 linhas e os e-mails com 3 | ✅ |
| 6 | Payload inválido: `encerramentoPrevistoEm: "amanhã"` | DLQ sem retry | `notificacoes.email.dlq` +1, "tentativa=1, categoria=DEFINITIVA" | ✅ |
| 7 | SMTP fora (`docker stop infra-mailpit-1`) com 2 e-mails e 1 interno | circuito abre, consumo de e-mail pausa, nada na DLQ, sino segue | circuito OPEN, "Consumidor de e-mail pausado"; 2 mensagens esperando em `notificacoes.email`, com 0 consumidores; o interno foi gravado | ✅ |
| 8 | SMTP de volta | retomada automática | HALF_OPEN → CLOSED, "Consumidor de e-mail retomado"; 2 e-mails entregues em ~9 s; DLQ sem mudança | ✅ |
| 9 | Retry pelas filas do backend: 2 falhas temporárias de banco (trigger com SQLSTATE 40001) | passa por `retry.interna.1` e `.2` e grava na 3ª tentativa | "Retry agendado: tentativa=2" e "tentativa=3"; a mensagem foi vista em `notificacoes.retry.interna.2`; 1 linha gravada; DLQ sem mudança | ✅ |
| 10 | ms parado com 3 eventos publicados | mensagens esperam e são consumidas na volta | 2 em `interna` e 1 em `email`, com 0 consumidores; na volta, 3 linhas no sino e 1 e-mail | ✅ |
| 11 | API sem token | 401 | 401 | ✅ |

Na primeira tentativa do cenário 9, a sequence do trigger foi criada pelo superusuário sem permissão para o usuário `notificacoes`. O erro `permission denied` (42501) foi classificado como definitivo e a mensagem foi para a DLQ, o que é o comportamento correto. Esse é o 2º item de `notificacoes.interna.dlq`. O cenário foi refeito com o `grant`.

## Achados

1. **E-mail de contato com domínio reservado vai para a DLQ.**
   - O `format: email` do networknt confere o TLD contra a lista da IANA e recusa `.local`, `.test`, `.internal` e `.lan`. `cliente@example.com`, `empresa.com.br` e `localhost` passam.
   - O cadastro do backend valida com `@Email` (Hibernate), que aceita `norte-...@ev.local`. Os dois RESOLUCAO reais do roteiro 7K foram para `notificacoes.email.dlq` por isso.
   - O backend também é inconsistente consigo mesmo: o `ContratoNotificacaoTest` usa o mesmo validador e recusaria esse payload.
   - Em produção os e-mails terão TLD real. Mesmo assim, um e-mail aceito no cadastro pode nunca ser enviado. **Decisão pendente:** validar o cadastro com a mesma regra do schema, ou relaxar o `format: email` no consumidor.
2. **App de dev sem rotinas agendadas desde 07:35 de 17/11.**
   - Um restart do devtools, provavelmente durante um `mvnw clean test` no host, que apaga o `target/classes` montado no container, subiu o contexto sem o `@EnableScheduling`. O "Condition evaluation delta" mostra o `TaskSchedulingAutoConfiguration` sem casar.
   - Por isso não rodavam o `PublicadorOutbox`, a rotina de violação de SLA nem a de encerramento automático. Os eventos ficavam pendentes com `tentativas = 0`.
   - Resolvido com `docker restart infra-backend-app-1`. Rodar a suíte no host com o app de dev ligado pode repetir o problema.
3. **`evidencias.processamento` está com 0 consumidores** no dev. Tem 3 mensagens na DLQ desde antes deste teste. Não foi investigado (fora do escopo).
4. **Transferência por inativação** (`TransferenciaChamados.transferir`) não grava notificação na outbox. O rascunho antigo do ms previa um aviso ao substituto. Conferir se a regra (RN-135) pede notificação.

## Estado deixado no dev

- **Container e imagem:** o container `ms-e2e` e a imagem `ms-notificacoes:e2e` foram removidos.
- **RabbitMQ:** com a topologia nova.
- **Banco `notificacoes`:** com as tabelas do ms (V2) e as notificações de teste.
- **DLQs:** `notificacoes.email.dlq` com 3 mensagens (2 do achado 1 e a do cenário 6) e `notificacoes.interna.dlq` com 2. Elas expiram em 7 dias.
- **Eventos de teste:** os `E2E r*` continuam em `outbox_eventos`, como publicados.
- **Volumes:** nenhum volume foi apagado.

## Como repetir

1. **Topologia:** `docker compose --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up rabbitmq-init`, seguido de `docker restart infra-rabbitmq-1`, no diretório `backend/`.
2. **Imagem do ms:** `docker build -t ms-notificacoes:e2e .`, no diretório `ms-notifications/`.
3. **Subir o ms:** `docker run --network flowsla-dev -p 8082:8081` com:
   - RabbitMQ: `RABBITMQ_HOST=rabbitmq`, usuário e senha do `app_principal`;
   - banco: `DB_URL=jdbc:postgresql://postgres:5432/notificacoes`, com `NOTIFICACOES_DB_USER` e `NOTIFICACOES_DB_PASSWORD`;
   - e-mail: `MAIL_HOST=mailpit`, `MAIL_PORT=1025`;
   - JWT: `KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/flowsla` e `KEYCLOAK_JWK_SET_URI=http://keycloak:8080/realms/flowsla/protocol/openid-connect/certs`.
4. **Gerar eventos:** inserir em `outbox_eventos`, com `exchange = notificacoes` e `routing_key` igual ao canal, um payload no formato de `docs/contratos/exemplos/validos`, com `eventoId` novo.
