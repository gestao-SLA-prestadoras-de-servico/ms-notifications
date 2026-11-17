# MS-NOT-08 — Evidências de retries e DLQ

Data local: 12/11/2026. Branch: `feature/ms-not-8-retris-dql`.
Stack isolada: `ms-not-08-test`, PostgreSQL 17.11, RabbitMQ 4.1, Mailpit 1.27 e Java 21.

## Execução

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'

docker compose -p ms-not-08-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it
docker compose -p ms-not-08-test -f compose.yaml -f infra/compose.postgres-it.yaml run --rm postgres-it

# Imagem final com backoff configurado para testar parada/reinício:
$env:RETRY_INITIAL_INTERVAL_MS = '5000'
docker compose -p ms-not-08-test up -d --build --force-recreate --wait --wait-timeout 240
```

## Resultados finais

| Verificação | Resultado observado | Status |
|---|---|---|
| Testes locais | 98 testes, sem falhas/erros/ignorados | Passou |
| Integração completa | 52 testes, sem falhas/erros/ignorados; `BUILD SUCCESS` | Passou |
| Perfil só PostgreSQL | 98 testes locais + 18 PostgreSQL; suites de mensageria excluídas; `BUILD SUCCESS` | Passou |
| SMTP 4xx/5xx tipado | Casos 421/450/451/499 e 500/550/599, inclusive falhas individuais de JavaMail | Passou |
| Saudação/autenticação SMTP | Códigos de resposta 421/554 e 454/535 extraídos de formatos do provider | Passou |
| Falhas de banco | SQLStates de conexão, serialização/deadlock, recursos, lock/query timeout classificados como temporários; integridade e SQL desconhecido como definitivos | Passou |
| Encadeamento | Causas, `getNextException()` e lista de erros SMTP percorridos; 5xx prevalece em erros mistos | Passou |
| Timeout até esgotamento | Quatro execuções reais do callback com headers 1/2/3/4 e backoff crescente; corpo preservado na DLQ | Passou |
| Sucesso em retry | Duas falhas temporárias seguidas de sucesso na terceira tentativa produziram uma notificação e um marcador | Passou |
| SMTP real 451 | Servidor SMTP TCP de teste respondeu 451 em RCPT; quatro comandos de envio/tentativas e DLQ | Passou |
| SMTP real 550 | Servidor SMTP TCP respondeu 550 em RCPT; uma tentativa e DLQ direta | Passou |
| Inválida | DLQ na tentativa 1 sem executar ação | Passou |
| Transporte indisponível | Falha de confirmação/publicação não causou ACK; origem foi mantida com NACK/requeue | Passou |
| Rota DLQ ausente | Binding removido, publicação mandatory devolvida e origem preservada; após restaurar a rota a mesma mensagem chegou à DLQ | Passou |
| Exchange ausente | Publicador detectou falha e lançou IOException sem tratar como sucesso | Passou |
| Consumidor parado | Retry atravessou parada/reinício do listener mantendo tentativa 2 | Passou |
| ACK/privacidade | Ordem publicação→ACK verificada; cópia persistente, sem expiração de origem e com próximo número; sem corpo/erro SMTP pessoal nos logs do handler | Passou |
| Regressões internas/e-mail | Persistência, rollback, idempotência, templates, Mailpit, falha SMTP e isolamento dos canais continuaram aprovados | Passou |

Total final: **150 testes distintos** (98 locais + 52 de integração). Dos testes
de integração, oito são novos em `RetryRabbitMQIT`; os 44 anteriores continuam
passando com as adaptações para publicação confirmada/filas de espera.

## Imagem final e reinício real do broker

Foi aplicada a configuração de backoff **5/10/20 segundos** na stack descartável
já existente, com recriação dos containers e preservação dos volumes. A API do
RabbitMQ confirmou, nas seis filas de retry, tipo quorum, TTL efetivo por etapa,
`dead-letter-strategy=at-least-once` e `overflow=reject-publish`. A DLQ interna
continuou com TTL `604800000` ms.

Uma trigger temporária, limitada a um `eventoId` fictício único no banco público
de testes, gerou SQLState `40001`. Uma sequência não transacional contou as
execuções efetivas, permitindo observar tentativas cujo trabalho foi revertido.

1. Publicado o evento na exchange/fila interna padrão, com entrega persistente.
2. Após a primeira falha, inspecionada sem remover a cópia na fila de espera: header da próxima tentativa igual a 2.
3. Aplicação parada, com a sequência ainda em 1; broker reiniciado mantendo seu volume.
4. Aplicação reiniciada e processamento retomado.
5. DLQ recebeu o mesmo evento com tentativa 4. A sequência estava em **4**, sem notificação parcial nem marcador de sucesso para o evento.
6. Logs da aplicação continham o registro final de DLQ na tentativa 4. Trigger, função e sequência temporárias foram removidas no `finally`.

Essa verificação usou a imagem de produção, as filas padrão, o contador real no
broker e PostgreSQL real. Não houve publicação pelo backend nem envio a contatos
reais. O resultado do probe foi registrado em `target/ms-not-08-runtime-probe.log`
(artefato local ignorado pelo Git).

## Falhas encontradas durante a implementação

- O primeiro build parou antes dos testes por ambiguidade de overload de `assertThat` em dois acessos genéricos a header. Os acessos foram trocados por valores do mapa de headers.
- A primeira execução integrada teve um erro no cenário de binding ausente: abrir a conexão de publicação fazia o RabbitAdmin redeclarar os beans e restaurar a rota removida, resultando em DLQ com uma mensagem quando o teste esperava zero. O teste passou a inicializar a conexão antes de remover o binding; então confirmou a falta real de rota e a preservação da origem.
- A execução seguinte teve uma falha no teste legado de recuperação: uma consulta imediata contou zero mensagens durante a transferência assíncrona entre filas. O teste passou a aguardar a mensagem visível na origem ou na fila de espera, em vez de presumir requeue imediato.
- Após essas correções, a suíte completa terminou sem falhas, erros ou testes ignorados. Os erros de SMTP/SQL/rota provocados intencionalmente permanecem como casos negativos esperados nos logs.

## Limites

- O limite é de quatro tentativas lógicas por cadeia. Reentregas físicas por falha de transporte e duplicação entre confirmação da cópia e ACK da origem continuam possíveis; a idempotência protege eventos concluídos.
- SMTP continua sujeito à janela de duplicação entre aceitação e commit, inclusive em envios parciais. Não há garantia de exatamente uma vez.
- O teste SMTP usa um servidor TCP local para respostas 451/550, além dos envios Mailpit das regressões; não foi usado provedor externo autenticado/TLS.
- Não foram simulados perda de disco, maioria indisponível em cluster ou failover entre hosts. O broker local tem um único nó.
- O circuit breaker/pausa automática pertence à MS-NOT-09.
- Não foram alterados schema da aplicação, credenciais, arquivos do backend ou containers da stack principal. A stack de teste foi encerrada sem apagar volumes.
- O smoke de infraestrutura da MS-NOT-03 não foi repetido nesta task; a topologia nova foi verificada diretamente no broker e no teste de reinício acima.

Configuração, atualização e reprocessamento: [README](../../README.md).
