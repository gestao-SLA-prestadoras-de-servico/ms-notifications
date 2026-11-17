# Testes — MS-NOT-15

Data: 12/11/2026. Branch consultada: `test/ms-not-14-15-reliability`.

## Reprodução

Com Java 21, Docker em execução e acesso às imagens e dependências, execute na raiz do microsserviço:

```powershell
.\mvnw.cmd -B -Presiliencia-it verify
```

Linux/macOS: `./mvnw -B -Presiliencia-it verify`. O comando empacota o JAR antes de iniciar a suíte de processos. Não precisa de `.env`, Keycloak ou backend em execução. Não combine com os outros perfis. As interrupções afetam somente os containers criados pela suíte.

Os relatórios JUnit ficam em `target/failsafe-reports`, os testes locais em `target/surefire-reports` e as evidências por fase em `target/ms-not-15/<execucao>/`. Os snapshots registram filas, consumidores, mensagens prontas/em trânsito, métricas e contagens de registros. Durante a indisponibilidade do processo, o arquivo de métricas registra essa condição.

## Execuções e limites

Execução final: **BUILD SUCCESS** no Java 21.0.12.1, com **161 testes locais e quatro testes de processos**, sem falhas, erros ou testes ignorados. Duração total: 3 minutos e 13 segundos. Identificador das evidências: `a38aa749-9648-46dd-b889-1961b6b42a8e`, em `target/ms-not-15/`. Log Maven: `target/ms-not-15-final.log`.

| Cenário | Resultado observado | Status |
|---|---|---|
| SMTP interrompido | Seis e-mails pendentes, circuito OPEN, consumidor de e-mail pausado; uma notificação interna persistida e liveness UP | Passou |
| SMTP restaurado | Seis e-mails capturados e seis marcadores EMAIL; fila vazia e circuito CLOSED; mesmo container sem reinício | Passou |
| PostgreSQL interrompido | Quatro falhas temporárias; uma mensagem na DLQ com tentativa 4 e categoria TEMPORARIA | Passou |
| Banco restaurado e DLQ reprocessada | Nenhuma notificação do evento antes do reprocessamento; uma notificação após republicação confirmada do corpo original | Passou |
| Serviço encerrado e broker reiniciado | Cinco mensagens persistentes confirmadas continuaram prontas após reiniciar o broker; a volta do serviço drenou as cinco | Passou |
| JVM encerrada no ACK | Processo terminou com código 37 após commit; entrega redelivered e uma notificação preservada | Passou |
| Reinício após perda de ACK | Fila drenada e contador de duplicata igual a um; total permaneceu em oito notificações e 14 marcadores, sem novo registro | Passou |
| Limpeza | Consulta Docker por label Testcontainers não retornou containers residuais | Passou |

As contagens finais incluem todos os cenários da execução: oito notificações internas e seis marcadores de e-mail, totalizando 14 marcadores de processamento. Durante a falha de banco, os snapshots marcam a indisponibilidade em vez de apresentar uma contagem inventada.

A primeira tentativa falhou na importação das definições do RabbitMQ com `IOException: EOF reached while reading`. O cliente de teste foi ajustado para HTTP/1.1 e a importação passou a aguardar a disponibilidade da API. A espera de recuperação também tolera falhas transitórias de conexão; o prazo continua limitado e o teste falha se a condição esperada não for atingida.

Na execução seguinte, SMTP e encaminhamento à DLQ passaram, mas a consulta ao PostgreSQL após o reinício usou a porta publicada antes do stop/start e atingiu o timeout. O Docker atribuiu uma nova porta ao mesmo container. A suíte passou a inspecionar a porta atual para banco, broker e aplicação após reinícios, sem substituir nem recriar seus dados. A comunicação interna entre os containers mantém as portas fixas.

Essa tentativa registrou três erros: timeout na consulta ao banco, timeout na inspeção do broker reiniciado e, por consequência, conflito HTTP 409 ao tentar encerrar novamente a aplicação que permanecera parada. O cenário SMTP passou. A repetição final usa a correção das portas para os três processos.

O produtor/outbox não foi executado: o backend disponível contém apenas a estrutura de pacotes desse módulo. Nenhum arquivo do backend foi alterado. A suite verifica mensagens aceitas pelo RabbitMQ com confirmação, sem afirmar cobertura da outbox ausente.
