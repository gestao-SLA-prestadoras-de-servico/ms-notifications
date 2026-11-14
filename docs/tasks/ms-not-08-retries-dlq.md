# MS-NOT-08 — Implementar retries e encaminhamento para DLQ

Requisitos: RF-61; RN-47, RN-50, RN-51, RN-56/RN-57 e falhas temporárias de banco.
Depende das MS-NOT-06 e MS-NOT-07.

| Critério / passo | Implementação |
|---|---|
| Inicial + até três retries | `ProcessadorEntrega` usa tentativa 1..4; contador segue no header persistente entre filas e entregas |
| Backoff exponencial configurável | Três filas quorum por canal; políticas TTL calculadas pelo init a partir do intervalo, multiplicador e teto |
| SMTP 5xx e inválidas direto à DLQ | `ClassificadorFalha` analisa códigos Angus, mensagens SMTP de saudação/autenticação, causas e exceções encadeadas |
| Timeout, SMTP 4xx, banco temporário | Replicação para a próxima etapa, com o corpo/identidade originais e próximo número de tentativa |
| Esgotamento registra falha | Publicação na DLQ do canal e log sem dados pessoais com canal, tentativa e categoria |
| Timeouts | SMTP e lock mantidos; timeout configurável de espera por publisher confirm |
| Retries não multiplicados | Uma ação por entrega; retry de template/containers explicitamente desligado; publicador sem retry próprio |
| DLQ antes de confirmar origem | Publicação persistente, mandatory, confirms correlacionados e verificação de returns antes do ACK |

## Decisões

- `ProcessadorEntrega` concentra a política dos dois listeners, mantendo o adaptador transacional da MS-NOT-05 e as ações concretas das MS-NOT-06/MS-NOT-07.
- A espera é feita pelo broker, sem bloquear a thread do consumidor ou depender de memória local. Cada fila de espera representa uma etapa, evitando que uma espera longa bloqueie o vencimento de uma espera curta no mesmo canal.
- As filas novas são quorum, com dead lettering `at-least-once` e `overflow=reject-publish`. A transferência de volta à fila de trabalho exige confirmação do destino. Filas classic existentes não são convertidas nem apagadas.
- TTL fica nas políticas, para permitir ajustar intervalos sem alterar argumentos imutáveis das filas. Os padrões são 1000/2000/4000 ms; gerador valida intervalo positivo, multiplicador finito maior que 1 e teto >= intervalo inicial.
- O contador `x-notificacoes-tentativa` é inteiro de 1 a 4; ausência equivale a 1 e header inválido é falha definitiva. A cópia é persistente e remove expiração específica da origem, deixando o TTL de cada fila controlar a espera/retenção.
- As exceções encadeadas são percorridas com proteção contra ciclos. SQLState de conexão, serialização/deadlock, falta de recursos, lock timeout e indisponibilidade é temporário; integridade/SQL desconhecido é definitivo. SMTP 5xx prevalece em erros mistos.
- Angus já era o provider runtime do starter; a dependência passa também a compile para ler os códigos SMTP tipados. Para respostas de saudação/autenticação que só expõem texto de protocolo, o código SMTP é extraído de formatos conhecidos, sem registrar a mensagem.
- Falha de confirmação, retorno por ausência de rota ou erro de publicação não confirma a origem. A mensagem fica recuperável por NACK/requeue. Falhas no ACK após commit não são tratadas como novas falhas de negócio.
- O publicador usa conexão de publicação separada, mandatory e CorrelationData, com callback de return que não expõe o corpo nos logs. A confirmação é individual, após o encaminhamento aceito.
- As DLQs mantêm TTL de sete dias. Não há nova tabela ou marcador de sucesso para tentativas fracassadas.

## Limites e operação

O máximo de quatro é por cadeia lógica, não uma promessa de exatamente quatro
execuções físicas sob qualquer queda de transporte: a mesma tentativa pode ser
reentregue. Uma queda após confirmar a cópia e antes do ACK da origem pode duplicar
a cópia; o marcador impede repetir eventos já concluídos. A janela SMTP entre
aceitação e commit e os envios parciais a vários contatos continuam podendo
produzir duplicação.

O circuit breaker/pausa de consumo permanece para MS-NOT-09. Até lá, cada cadeia
temporária esgota suas três novas tentativas e segue à DLQ. Indisponibilidade do
próprio encaminhamento preserva a origem e pode gerar novas entregas de transporte,
sem inventar sucesso ou descartar a mensagem.

A importação das novas definições/políticas exige boot do RabbitMQ. Em ambiente
existente, usar a sequência documentada de recriação com volumes preservados;
reconstruir só a aplicação não importa a nova topologia. Não usar `down -v`.

Configuração, atualização e reprocessamento manual: [README](../../README.md).
Evidências: [relatório de testes](../testes/ms-not-08-retries-dlq.md).
