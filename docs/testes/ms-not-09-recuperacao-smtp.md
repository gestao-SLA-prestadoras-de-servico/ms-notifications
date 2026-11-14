# MS-NOT-09 — Evidências de circuit breaker e recuperação SMTP

Data: 12/11/2026. Branch: `feature/ms-not-9-breaker-smtp`.
Projeto Docker isolado: `ms-not-09-test`, Java 21, PostgreSQL 17, RabbitMQ 4.1 e Mailpit 1.27.

## Execução reproduzível

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'

docker compose -p ms-not-09-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it
docker compose -p ms-not-09-test -f compose.yaml -f infra/compose.postgres-it.yaml run --no-deps --rm postgres-it

# Aplicação final na mesma stack isolada; não recria banco/broker.
docker compose -p ms-not-09-test up -d --build --no-deps --wait --wait-timeout 240 ms-notificacoes
python infra/smoke_smtp.py

# Ao terminar, preserve os volumes e os dados de inspeção.
docker compose -p ms-not-09-test down
```

O smoke exige filas padrão de e-mail/DLQ vazias, consumidores e circuito habilitados, janela/mínimo iguais a 1 e parâmetros de espera compatíveis com o timeout de 90 segundos. Recusa configuração incompatível antes de interromper o SMTP. Seu projeto está fixado em `ms-not-09-test`; não executa purge nem remove volumes. O Mailpit é reiniciado em `finally`, inclusive quando uma asserção falha.

## Resultados

| Verificação | Resultado |
|---|---|
| Suíte local | 115 testes, sem falhas, erros ou ignorados |
| Integração completa | 53 testes, sem falhas, erros ou ignorados; `BUILD SUCCESS` |
| Perfil PostgreSQL independente | 115 testes locais + 18 de PostgreSQL, sem falhas/erros/ignorados; `BUILD SUCCESS`; suítes de RabbitMQ/SMTP excluídas |
| Imagem final e smoke com Mailpit parado | Imagem saudável; circuito OPEN, consumidor pausado, tentativas 3/3/3/4 preservadas; recuperação HALF_OPEN → CLOSED e quatro e-mails capturados |
| Revisão de whitespace | `git diff --check` sem erros |

Logs locais de execução, ignorados pelo Git: `target/ms-not-09-it-final.log`, `target/ms-not-09-postgres-it.log` e `target/ms-not-09-runtime.log`.

A stack isolada foi encerrada ao final, sem remover volumes. Foram preservados os volumes de PostgreSQL, RabbitMQ, definições e cache Maven. Os quatro containers da stack principal permaneceram em execução e saudáveis; não foram recriados ou atualizados nesta validação.

## Interrupção real do Mailpit com a imagem final

`infra/smoke_smtp.py` foi executado com os parâmetros padrão da aplicação: janela/mínimo 1, limiar 100%, espera de 30 segundos e intervalo de sondagem de 5 segundos.

1. A aplicação iniciou saudável, com a migration no schema próprio e filas de e-mail/DLQ vazias.
2. O container Mailpit foi parado; publicou-se um e-mail já na tentativa 4. O circuito abriu, o consumidor pausou e a mensagem permaneceu na origem, sem ir à DLQ.
3. Mais três e-mails na tentativa 3 acumularam, enquanto um evento interno foi processado e confirmado no banco. Inspeção sem remover as mensagens confirmou os headers 3/3/3/4, nenhum marcador de e-mail e DLQ vazia.
4. O Mailpit foi iniciado novamente. A sonda independente passou por HALF_OPEN e fechou o circuito; o consumidor retomou automaticamente.
5. A fila drenou, os quatro marcadores de e-mail foram gravados, quatro mensagens do caso foram capturadas pelo Mailpit e a DLQ permaneceu vazia.

Saída final do roteiro:

```text
OK: Mailpit stopped, circuit OPEN, consumer paused, headers 3/3/3/4 preserved, internal committed, DLQ empty
OK: Mailpit restarted, scheduled probe HALF_OPEN -> CLOSED, queue drained, four emails captured, four success markers
```

## Cobertura nova

- Conexão recusada abre o circuito e bloqueia o envio seguinte; sonda com falha mantém OPEN e uma sonda bem-sucedida fecha o circuito.
- SMTP 421 é indisponibilidade; SMTP 450/451/452 e 5xx não abrem o circuito. 5xx prevalece em erros mistos. EOF também continua temporário quando a abertura requer mais de uma chamada ou a proteção está desabilitada.
- Falha de banco não alimenta o circuito SMTP. Configuração com mínimo de duas chamadas mantém a primeira falha no fluxo limitado e abre após a segunda, respeitando a espera configurada.
- Controle pausa apenas o container de e-mail, espera CLOSED para retomar e respeita consumidor desabilitado e encerramento do contexto.
- Listener com circuito aberto devolve a entrega antes do processamento de contrato/banco/retries. A falha que abre o circuito devolve a mensagem sem ACK, cópia em retry/DLQ ou incremento nas tentativas 1, 3 e 4.
- `RecuperacaoSmtpIT` usa três consumidores, prefetch 5, listener real, PostgreSQL, RabbitMQ e SMTP real. A porta SMTP é trocada para uma porta sem serviço, causando recusa TCP também na sonda.
- O teste preserva a primeira entrega na tentativa 3 e mais dez entregas na tentativa 4. Enquanto OPEN, a fila contém as onze mensagens, retries/DLQ permanecem vazios e o marcador interno é persistido.
- Reinício manual do listener durante OPEN não envia e-mails; depois da nova pausa, os onze headers são conferidos individualmente. A recuperação automática drena as onze entregas, grava onze marcadores de e-mail e captura exatamente onze mensagens na busca por assunto exclusiva do caso.
- A suíte anterior de envio roda com circuito desabilitado para manter a cobertura de retries durante falha SMTP. As suítes anteriores de idempotência, persistência, encaminhamento e canais continuam incluídas na execução completa.

## Ajustes encontrados durante a validação

A primeira execução completa identificou uma asserção antiga que consultava a fila imediatamente depois de cancelar o consumidor. Ela foi ajustada para aguardar a devolução assíncrona do NACK ao RabbitMQ, conferindo também que a DLQ continua vazia.

Na repetição, o teste novo usava o campo `total` da busca Mailpit, que inclui toda a caixa postal e os dados de execuções anteriores. A verificação foi corrigida para `messages_count`, com busca por assunto exclusivo do caso. As falhas iniciais eram das verificações; a suíte completa posterior passou.

## Limites

O teste operacional e os testes de integração exercitam o microsserviço com seus serviços reais. Não representam publicação ponta a ponta pelo backend; nenhum arquivo desse projeto foi alterado.

Conexão/TLS/autenticação válidas na sonda não garantem aceitação futura de todos os destinatários. SMTP não participa da transação PostgreSQL: aceitação seguida de queda antes do commit, ou envio parcial a um lote, ainda pode produzir duplicação. O circuito é local à instância e reinicia fechado; mensagens e tentativas continuam duráveis no broker.

Referências: [circuit breaker Resilience4j](https://resilience4j.readme.io/docs/circuitbreaker), [parada do container AMQP](https://docs.spring.io/spring-amqp/api/org/springframework/amqp/rabbit/listener/AbstractMessageListenerContainer.html#setForceStop(boolean)) e [schema oficial da API Mailpit](https://raw.githubusercontent.com/axllent/mailpit/master/server/ui/api/v1/swagger.json).
