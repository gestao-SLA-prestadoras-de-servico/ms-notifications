# Operação do ms-notificacoes

## Inicialização e integração

Siga os [pré-requisitos e comandos Docker](../README.md#ambiente-docker--ms-not-03). Configure credenciais próprias no `.env` local e preserve os volumes existentes. Em execução direta, exporte as variáveis do [.env.example](../.env.example): o Spring não carrega `.env` automaticamente. O serviço aplica migrations Flyway ao iniciar; para mudança de schema, acrescente uma migration e preserve as já aplicadas.

O app-principal deve publicar eventos persistentes com publisher confirms e retorno de mensagens não roteadas na exchange direta `notificacoes`, usando `email` ou `interna`. O [contrato de eventos](contratos/eventos.md) define os campos e destinatários. A integração completa depende da implementação do publicador/outbox no backend; não está comprovada apenas pela execução isolada deste serviço.

O frontend envia JWT Bearer do Keycloak para a [API OpenAPI](openapi.json). Configure o issuer público exato, o endereço JWKS acessível ao serviço, a audiência e as origens CORS. Os papéis de realm `TECNICO` e `GESTOR` autorizam a API; o destinatário vem do `sub` validado. Consulte os detalhes e exemplos no [guia JWT e API](../README.md#jwt-do-keycloak--ms-not-10).

## Diagnóstico

1. Confira `docker compose ps` e os logs de `ms-notificacoes`, `rabbitmq` e `postgres`. A porta HTTP no host é informada por `docker compose port ms-notificacoes 8081`.
2. Consulte `/actuator/health/liveness` para a saúde do processo e `/actuator/health` para dependências. SMTP indisponível pode afetar a saúde agregada enquanto a liveness continua UP. O endpoint `/actuator/prometheus` expõe métricas; restrinja seu acesso à rede de monitoramento em produção.
3. No RabbitMQ Management, examine profundidade, mensagens não confirmadas, consumidores, filas de retry e DLQs para cada canal. O [guia de observabilidade](../README.md#observabilidade--ms-not-13) descreve Prometheus e Grafana.
4. Se SMTP falhar, verifique configuração, conectividade e circuito; o consumidor de e-mail pausa e a sondagem retoma o consumo após a recuperação. O canal interno permanece independente. Veja [recuperação SMTP](tasks/ms-not-09-recuperacao-smtp.md).

## DLQ e reprocessamento

As filas `notificacoes.email.dlq` e `notificacoes.interna.dlq` possuem TTL de sete dias. Investigue a categoria e a causa antes da expiração. Ao inspecionar uma mensagem no Management, use a opção que a devolve à fila; a leitura com confirmação a remove. Evite expor payloads e endereços em logs ou chamados.

Após corrigir a causa, use um cliente AMQP com ACK manual para receber a mensagem da DLQ e republique o corpo original na exchange `notificacoes` com a routing key do canal, mensagem persistente e o mesmo `eventoId`. Remova os headers `x-notificacoes-tentativa` e `x-notificacoes-categoria` para iniciar uma nova cadeia. Use publisher confirms e mandatory/returns; confirme a publicação e o roteamento antes do ACK da mensagem da DLQ. Na falha de publicação, feche o canal sem ACK para que a origem seja devolvida à DLQ. Uma falha entre confirmação da cópia e ACK da origem pode duplicar a mensagem; monitore a fila e preserve os marcadores. Não faça purge ou remova volumes para resolver uma falha.

## Decisões e limites

O PostgreSQL coordena o evento por `(eventoId, canal)` com lock transacional e marcador de sucesso; o ACK ocorre após o commit. Em e-mail há uma janela entre aceitação SMTP e commit, e envios parciais a vários destinatários também podem resultar em duplicações após falha. Veja [idempotência](tasks/ms-not-05-idempotencia-concorrencia.md) e [retries/DLQ](tasks/ms-not-08-retries-dlq.md).

O expurgo remove notificações internas anteriores ao limite de 90 dias em lotes e mantém marcadores de processamento. E-mails e payloads não são persistidos nas tabelas do serviço. A DLQ tem política distinta de sete dias; a retenção não substitui controles de acesso e minimização de logs. Veja [retenção](tasks/ms-not-12-retencao-minimizacao.md).

Resultados e limites dos testes reproduzíveis: [recuperação e isolamento](testes/ms-not-15-recuperacao-isolamento.md) e [escala com uma e quatro instâncias](testes/ms-not-16-escalabilidade.md).
