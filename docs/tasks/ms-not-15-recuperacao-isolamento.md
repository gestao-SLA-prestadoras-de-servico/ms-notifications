# MS-NOT-15 — Recuperação de falhas e isolamento dos canais

Requisitos RN-46 a RN-51, RN-55 a RN-58 e RN-115; depende das MS-NOT-09, MS-NOT-13 e MS-NOT-14.

O perfil `resiliencia-it` executa o JAR empacotado pelo Maven em um processo separado, com PostgreSQL, RabbitMQ e Mailpit reais provisionados pelo Testcontainers. As falhas afetam exclusivamente esses containers temporários. A suíte funcional da MS-NOT-14 permanece no perfil `completo-it`; os perfis não devem ser combinados.

| Critério | Prova automatizada em ResilienciaProcessosIT |
|---|---|
| Falha SMTP abre circuito e acumula fila | Pausa real do Mailpit; seis mensagens pendentes, consumidor de e-mail pausado e métrica OPEN |
| Canal interno permanece operacional | Evento interno persistido durante a pausa, com liveness UP |
| Recuperação drena e-mail | Retomada do mesmo Mailpit, seis sucessos e seis mensagens capturadas, circuito CLOSED e nenhum reinício da aplicação |
| Falha real de banco, retries e DLQ | PostgreSQL encerrado abruptamente; quatro falhas temporárias, mensagem na DLQ com tentativa 4 e nenhum registro interno; após recuperação, reprocessamento do corpo original |
| Persistência após reinícios | Serviço encerrado por SIGKILL, cinco publicações persistentes com mandatory/confirm, reinício do mesmo broker, cinco entregas preservadas e drenadas ao reiniciar o serviço |
| Queda entre commit e ACK | JVM de teste encerrada por Runtime.halt(37) exatamente no ACK; registro confirmado, entrega marcada redelivered e métrica de duplicata após reiniciar o serviço |

O processo `ProcessoAntesDoAck` pertence somente ao código de teste. Ele usa o processador transacional e o persistidor da aplicação, interceptando exclusivamente o ACK para encerrar a JVM. O JAR de produção não recebe hooks de falha. Isso complementa o teste anterior que simulava IOException no ACK com uma queda real da JVM.

As evidências ficam em `target/ms-not-15/<execucao>/`: snapshots JSON das filas e contagens persistidas, métricas Prometheus por fase e logs dos processos. Contadores da aplicação reiniciam junto com a JVM; a comparação deve levar esse reinício em conta. A medição do RabbitMQ pode levar alguns segundos para refletir entregas e consumidores, por isso os testes aguardam condições verificáveis.

O banco e o broker mantêm seus dados durante reinícios do mesmo container. Ao encerrar a suíte, todos os seus recursos descartáveis são removidos; isso não afeta volumes ou containers da stack do usuário. O teste de reprocessamento confirma a publicação antes de confirmar a retirada da DLQ e preserva o eventoId. A inspeção da reentrega usa basicGet com ACK manual e devolve a entrega antes de iniciar o consumidor normal.

## Integração com o produtor

Foi consultado, somente para leitura, o backend em `C:\Users\fdant\workspace\Integrador2026\backend`. O diretório `src/main/java/com/gestaosla/backend/outbox` possui somente `api/package-info.java` e `internal/package-info.java`: não há publicador/worker de outbox executável nessa versão. O cenário RN-113/RN-114 de broker indisponível com outbox do produtor permanece dependente dessa implementação. A publicação confirmada do teste não é apresentada como substituta de uma outbox.

Execução e resultados: [relatório de testes](../testes/ms-not-15-recuperacao-isolamento.md).
