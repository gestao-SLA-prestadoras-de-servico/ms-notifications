# MS-NOT-12 — Retenção e minimização

RN-57, RN-59 e seção 6.4; depende das MS-NOT-04, MS-NOT-06 e MS-NOT-07.

| Critério | Implementação |
|---|---|
| Expurgo diário após 90 dias | Agendamento cron e fuso configuráveis; limite fixo calculado uma vez por execução pelo Clock |
| Preservar dados dentro do prazo | Predicado estrito `criado_em < limite`; a fronteira de exatamente 90 dias permanece |
| Exclusão em lotes | DELETE com subconsulta limitada, ordenada e `FOR UPDATE SKIP LOCKED`; transação independente por lote |
| Preservar idempotência | Nenhuma operação sobre `evento_processado`; sem FK/cascade na V1 |
| Minimizar dados pessoais | Entidades de email não existem; logs do consumidor não imprimem payload nem exceções; provider SMTP opera sem debug |
| DLQ por sete dias | Ambas as filas possuem `x-message-ttl=604800000` |

## Decisões e limites

- A rotina usa índice de criação existente; nenhuma migration é necessária. A retenção é 90 dias de 24 horas, independente de mês/fuso. O fuso afeta somente o momento diário de início.
- Várias instâncias podem executar ao mesmo tempo: linhas bloqueadas são ignoradas; se ainda estiverem bloqueadas ao terminar, ficam para uma execução posterior. Lotes já confirmados sobrevivem a falhas posteriores.
- O contador de expurgo registrado não contém destinatário, evento ou conteúdo. Uma falha de banco interrompe o job e a execução seguinte retoma o trabalho; o agendador padrão registra o erro.
- `TratadorFalhaAmqp` preserva a estratégia de classificação de falhas fatais, incluindo rejeição manual e tratamento de `x-death`, sem imprimir mensagem ou texto da exceção. As factories simple/direct continuam configuradas pelo Spring Boot para manter ACK/concurrency/prefetch do ambiente.
- O logger `org.springframework.amqp.rabbit.listener` fica em ERROR: o container pode registrar o corpo de uma entrega em WARN durante o encerramento. Essa opção também suprime outros avisos desse logger; a observabilidade adicional pertence à MS-NOT-13.
- O tráfego de RabbitMQ contém os campos necessários ao processamento, inclusive e-mail e posicionamento; essas informações não são gravadas nas tabelas nem nos logs de aplicação sob configuração padrão. Não ativar debug de protocolo/SQL com payload em ambientes reais.
- Marcadores permanecem indefinidamente para evitar reprocessamento; a política de retenção futura deles exige outra decisão explícita. IDs de evento podem conter identificadores de chamados.
- A topologia prevê TTL de sete dias para DLQs. O TTL vale a partir da entrada em cada fila; filas de trabalho e retry são temporárias e não possuem o TTL da DLQ.

Configuração e operação: [README](../../README.md#retenção-e-minimização--ms-not-12). Evidências: [testes](../testes/ms-not-12-retencao-minimizacao.md).
