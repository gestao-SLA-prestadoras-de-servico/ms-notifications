# Contrato dos eventos de notificação

O contrato é do app-principal, que é o produtor. A fonte única são os JSON Schemas
(draft 2020-12) de `backend/contratos/notificacoes/<tipo>.schema.json`, gerados dos
records de `outbox.api.EventoNotificacao` (outbox-04). As regras de transporte estão em
`backend/docs/tasks/outbox/CONVENÇÕES_RABBITMQ.md`.

O ms-notificacoes guarda uma cópia idêntica dos schemas em
[`src/main/resources/contratos/notificacoes/`](../../src/main/resources/contratos/notificacoes).
O `LeitorEventoNotificacaoTest` falha se a cópia divergir do backend. Mudar o contrato exige
combinar com o backend, copiar os schemas novos e ajustar o consumidor **antes** de o backend publicar o formato novo.

> Histórico: até a integração com o backend (ver [`../testes/integracao-backend.md`](../testes/integracao-backend.md)),
> este documento descrevia uma proposta própria do ms. Ela usava `numero` como string, os tipos
> `VIOLACAO_SLA`, `PRIMEIRO_POSICIONAMENTO` e `REATRIBUICAO`, e não tinha `relogio` nem
> `encerramentoPrevistoEm`. Nenhuma mensagem real do backend passava nessa validação.

## Transporte

- **Corpo:** JSON UTF-8 publicado na exchange direta `notificacoes`, com routing key `email` ou `interna`.
- **Propriedades AMQP:** `message_id` = `eventoId`, `type` = tipo do evento, entrega persistente.
- **Ordem das chaves:** não é garantida, porque o payload passa por uma coluna `jsonb`.
- **Entrega:** pelo menos uma vez. O consumidor descarta repetições pelo `eventoId`.

## Campos

Todo campo do schema é obrigatório, e campo a mais é recusado (`additionalProperties: false`).

| Campo | Tipo | Conteúdo |
|---|---|---|
| `eventoId` | string `uuid` | Chave de idempotência; igual ao `message_id`. |
| `tipo` | string | Um dos 8 tipos abaixo. |
| `chamado.id` | string `uuid` | Identificador do chamado; usado no link do sino. |
| `chamado.numero` | inteiro | Número legível. O ms exibe `#1042`. |
| `titulo` | string não vazia | Título do chamado. |
| `destinatarios` | lista não vazia | Canal `interna`: `sub` do Keycloak (uuid) de técnicos e gestores. Canal `email`: exatamente 1 e-mail, o de contato do cliente. |
| `relogio` | `RESPOSTA`, `ATENDIMENTO_CAMPO` ou `RESOLUCAO` | Só em `VIOLACAO`: qual SLA foi violado. |
| `textoPosicionamento` | string não vazia | Só em `POSICIONAMENTO`. |
| `encerramentoPrevistoEm` | string `date-time` | Só em `AVISO_ENCERRAMENTO`: quando o chamado será validado automaticamente. |

## Tipos

| `tipo` | Canal | Destinatários | O que o ms produz |
|---|---|---|---|
| `ATRIBUICAO` | interna | técnico atribuído ou reatribuído | sino |
| `REMOCAO` | interna | técnico anterior, na reatribuição | sino |
| `REABERTURA` | interna | técnico atribuído | sino |
| `CONCLUSAO` | interna | gestores com a preferência ativa no chamado | sino |
| `VIOLACAO` | interna | todos os gestores e o técnico atribuído | sino, citando o relógio violado |
| `POSICIONAMENTO` | email | contato do cliente | e-mail com o texto do posicionamento |
| `RESOLUCAO` | email | contato do cliente | e-mail |
| `AVISO_ENCERRAMENTO` | email | contato do cliente | e-mail com a data do encerramento (horário de Brasília) |

A reatribuição não tem tipo próprio: o backend envia `ATRIBUICAO` para o técnico novo e
`REMOCAO` para o anterior, cada um com seu `eventoId`.

## Validação

`LeitorEventoNotificacao.ler(payload)` valida o JSON com o schema do tipo, com `format`
conferido (`formatAssertionsEnabled(true)`), e só então o mapeia para `EventoNotificacao`.
No draft 2020-12, `format` é só anotação por padrão: sem essa opção, `"amanhã"` passaria
como `date-time`.

A sobrecarga `ler(payload, canal)` verifica também se o tipo pertence à fila. Toda falha
lança `EventoInvalidoException`, sem payload nem causa, porque eles podem conter dados
pessoais. O consumidor manda a mensagem direto para a DLQ, sem nova tentativa.

## Exemplos

- [`exemplos/validos`](exemplos/validos): um payload por tipo, no formato do backend.
- [`exemplos/invalidos`](exemplos/invalidos): campos ausentes, uuid inválido, `numero` como texto, tipo antigo, relógio desconhecido, data inválida, dois e-mails, campo a mais e outros casos.

Os dois conjuntos são lidos pelo `LeitorEventoNotificacaoTest` (`./mvnw test`). Os dados são fictícios.

E-mail de contato e texto do posicionamento só trafegam na mensagem: não são persistidos
nem registrados em logs, e o `toString` do evento os omite.
