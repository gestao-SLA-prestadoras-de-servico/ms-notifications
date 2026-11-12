# MS-NOT-05 — Validação com o checkout do backend

Data local: 11/11/2026.

Repositórios inspecionados:

- `C:/Users/fdant/workspace/Integrador2026/backend` — somente leitura.
- `C:/Users/fdant/workspace/Integrador2026/ms-notifications` — código e evidências da MS-NOT-05.

## Conclusão

**A comunicação operacional backend → RabbitMQ → ms-notificacoes ainda não está
disponível no estado inspecionado.** A MS-NOT-05 possui coordenação e adaptador
testados isoladamente com PostgreSQL/RabbitMQ reais, mas isso não comprova o fluxo
integrado com um evento produzido pelo backend.

O checkout do backend ainda tem somente os descritores de pacote nos módulos
`outbox`, `chamado` e `sla`. Não foram encontrados DTOs de eventos de notificação,
registro/publicador da outbox, geração determinística de `eventoId` de SLA ou
chamadas de publicação AMQP no código Java atual. O microsserviço também ainda
não tem listeners automáticos; as ações/consumidores concretos pertencem às
MS-NOT-06/MS-NOT-07.

## Achados que impedem a integração

| Achado | Evidência | Consequência |
|---|---|---|
| Produtor de notificações ausente | `backend/src/main/java/com/gestaosla/backend/outbox/{api,internal}` contém somente `package-info.java`; busca por `RabbitTemplate`, `AmqpTemplate`, `convertAndSend` e `basicPublish` não encontrou publicação no backend | Não há evento real de negócio para validar payload, preservação de ID/destinatários, outbox ou publisher confirms |
| Topologia ainda não alinhada | `backend/infra/rabbitmq/generate_definitions.py:49` gera somente `outbox.eventos` do tipo topic; `ms-notifications/docs/topologia.json` usa `notificacoes` do tipo direct, chaves `email`/`interna` e respectivas filas | Não existe ligação implementada/documentada entre essas exchanges. O próprio backend diz que a topologia mínima será completada em outbox-02 |
| Brokers/redes separados | Backend usa rede `flowsla-${ENVIRONMENT}` e seu serviço RabbitMQ; microsserviço usa seu próprio serviço RabbitMQ na rede Compose `ms-notificacoes_default` | O hostname `rabbitmq` identifica serviços distintos em cada rede. Iniciar os dois Composes atuais não os conecta ao mesmo broker |
| Divergência com a decisão registrada no backend | `backend/docs/tasks/plataforma-02-completar-docker-compose-e-dockerfile.md:49` determina infraestrutura compartilhada no Compose do backend, com entrada externa do microsserviço em `flowsla-dev` | O modo independente atual do microsserviço atende testes locais, mas ainda falta um modo de integração compatível com essa decisão |
| Consumidores não conectados ao adaptador | Não há `@RabbitListener` ou `basicConsume` no código de produção do microsserviço | Um payload publicado na fila não será processado automaticamente nesta etapa |

O nome `outbox.eventos` é infraestrutura mínima do backend, não um contrato de
publicação já implementado. Portanto, a diferença de exchanges é um ponto
pendente de alinhamento, não prova de que um publicador existente envia para a
exchange errada. As configurações de usuário usam nomes diferentes
(`RABBITMQ_USER` no backend e `RABBITMQ_USERNAME` no microsserviço); isso também
exige mapeamento para as mesmas credenciais/vhost no ambiente integrado, mas não
é, sozinho, uma incompatibilidade de protocolo.

## Verificação do Docker em execução

Foram executadas consultas somente leitura:

```text
docker ps -a
docker network ls
docker inspect <containers> --format <estado e redes>
docker exec ms-notificacoes-rabbitmq-1 rabbitmqctl list_queues name durable messages consumers arguments
docker exec ms-notificacoes-rabbitmq-1 rabbitmqctl list_exchanges name type
docker exec ms-notificacoes-rabbitmq-1 rabbitmqctl list_bindings source_name destination_name routing_key
```

Resultados observados:

- Os quatro serviços da stack `ms-notificacoes` estavam em execução e saudáveis.
- `infra-backend-app-1` e `infra-rabbitmq-1` estavam parados (`exited`), associados à rede `flowsla-dev`.
- Aplicação e RabbitMQ do microsserviço estavam na rede `ms-notificacoes_default`.
- O broker ativo tinha `notificacoes`/`notificacoes.dlx` direct e os bindings `email`/`interna` corretos.
- As quatro filas de notificações eram duráveis. As duas DLQs tinham TTL `604800000` ms.
- As filas `notificacoes.email` e `notificacoes.interna` tinham **zero consumidores** e zero mensagens.
- O broker ativo não tinha a exchange `outbox.eventos`.

Nenhuma mensagem foi publicada, consumida ou apagada nessa auditoria. Nenhum
container foi iniciado/reiniciado e nenhuma rede foi alterada. Não se iniciou o
Compose do backend: seu init gera arquivos no checkout, e não há publicador
implementado que permita completar o teste solicitado.

## Critérios da MS-NOT-05

| Critério / passo | Camada do microsserviço | Integração com o backend |
|---|---|---|
| Reconhecer e descartar sucesso em reentrega | Implementado: consulta após lock e retorno `JA_PROCESSADO` sem ação | Pendente de publicador real |
| Notificações e sucesso na mesma transação | Implementado no coordenador: ação síncrona participante e marcador antes do commit; testes de rollback e falha no commit | Pendente dos consumidores e eventos reais |
| Concorrência sem duplicação interna | Implementado com lock PostgreSQL por evento/canal e unicidade por evento/sub | Não exercitado entre processos do backend e consumidores de produção |
| Limitação de duplicação SMTP documentada | Atendido no README e na documentação da task | Envio SMTP real ainda não implementado |
| Identidade por evento/canal e destinatário | Marcador único por evento/canal e notificação única por evento/sub | Preservação do ID e destinatários pelo backend não verificável sem produtor |
| Coordenação pelo banco | `pg_advisory_xact_lock`, `READ_COMMITTED`, timeout de espera e liberação no commit/rollback | Requer todos os consumidores usando esse coordenador e o mesmo banco do microsserviço |
| ACK após conclusão | Adaptador executa `basicAck(tag, false)` após o retorno do commit; ACK manual configurado | Ainda não conectado a listeners automáticos |
| Marcadores separados da exclusão | Tabelas independentes, sem FK/cascade; reentrega após excluir notificação continua descartada | Independente do backend |

As evidências da execução anterior da MS-NOT-05 foram consultadas em
`target/ms-not-05-it.log`: 37 testes locais e 21 testes de integração, com
`BUILD SUCCESS`. A suíte **não foi reexecutada nesta auditoria**, porque não houve
mudança de código; os 58 testes anteriores usam fixtures/publicação de teste e
não eventos produzidos pelo backend. Detalhes em
[ms-not-05-idempotencia-concorrencia.md](ms-not-05-idempotencia-concorrencia.md).

## Próximos passos para comprovar a comunicação

1. Implementar/alinhar no backend o DTO e o registro/publicador da outbox, preservando ID e conteúdo nas republicações, com persistência, confirms e tratamento de ausência de rota. Validar também o ID determinístico de SLA.
2. Disponibilizar no microsserviço um modo de integração com o broker do backend na rede `flowsla-dev`, conforme a decisão registrada, sem subir um segundo broker para esse modo. Alinhar vhost, credenciais, exchange, filas e bindings. Se também seguir o PostgreSQL compartilhado descrito no backend, o banco de notificações provisionado lá se chama `notificacoes`, enquanto o modo independente atual usa `ms_notificacoes`; cada banco continua exigindo usuário próprio.
3. Conectar os listeners das MS-NOT-06/MS-NOT-07 ao adaptador idempotente.
4. Executar uma operação real de negócio, observar a outbox/publicação e o processamento, republicar o mesmo evento e comprovar uma notificação por destinatário e um marcador por evento/canal. Exercitar também reentrega após perda do ACK.

Nenhuma dessas implementações foi feita como parte desta revisão. O único
arquivo novo desta auditoria é este relatório, dentro de `ms-notifications`.
