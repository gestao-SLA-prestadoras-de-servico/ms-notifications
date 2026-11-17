# MS-NOT-05 — Implementar idempotência e controle de concorrência

Requisitos: RN-55 e RN-115, com identidade de evento da RN-54.
Dependências: MS-NOT-02 e MS-NOT-04.

| Critério | Solução |
|---|---|
| Reentregas com sucesso são descartadas | Consulta por evento/canal após o lock; retorno `JA_PROCESSADO` sem executar a ação |
| Notificações e sucesso na mesma transação | Ação síncrona e inserção do marcador no mesmo `TransactionTemplate`; retorno após commit |
| Concorrência não cria notificações duplicadas | Advisory lock transacional PostgreSQL por evento/canal e restrições únicas já existentes na V1 |
| Limitação SMTP documentada | Se o envio for aceito antes de falha no registro de sucesso, uma reentrega poderá enviar novamente |
| ACK após conclusão | Adaptador valida contrato/canal, executa o coordenador e confirma uma única entrega após o commit, inclusive repetições |
| Marcadores independentes da exclusão | Sem relação/cascade com notificações; marcador continua bloqueando reprocessamento após exclusão |

## Decisões

- O lock usa `pg_advisory_xact_lock(hashtextextended(chave, 0))`, com prefixo do serviço, canal e evento. Não exige tabela de reservas nem marca um evento como processado antes do sucesso. Não foi necessário alterar a V1 ou criar nova migration.
- `REQUIRES_NEW` faz o retorno significar commit concluído mesmo dentro de outra transação. A ação participa por `REQUIRED` e não deve criar transações independentes ou executar trabalho assíncrono.
- `READ_COMMITTED` garante leitura atualizada do marcador depois de aguardar outra instância. Um snapshot repetível anterior ao lock poderia ocultar o sucesso recém-confirmado.
- Colisões do hash apenas serializam chaves diferentes; a existência do marcador é consultada com a identidade completa. Canais e eventos diferentes têm locks independentes.
- A espera é limitada por `PROCESSAMENTO_LOCK_TIMEOUT`, padrão `10s`, enviada por parâmetro ao `set_config` local da transação. Falhas são propagadas, sem tentativa adicional nesta camada.
- O instante de sucesso vem de `Clock` injetado, depois da ação. Não há logging de payload, destinatários de e-mail ou texto do posicionamento.
- A unidade interna é o conjunto completo de destinatários do evento, com unicidade por evento/sub na tabela de notificações. Republicações devem preservar ID e destinatários.
- O adaptador exige ACK manual, confirma apenas a entrega atual (`multiple=false`) e propaga erros de ACK. Não tenta desfazer um processamento já confirmado no banco se a conexão RabbitMQ falhar depois.

Os listeners e as ações concretas de composição/persistência interna e SMTP
serão conectados nas MS-NOT-06 e MS-NOT-07. A política de falhas/NACK/retry/DLQ
pertence à MS-NOT-08. Nesta etapa há coordenador e adaptador executáveis, sem
listeners que possam consumir mensagens antes de essas ações existirem.

## SMTP: janela de duplicação assumida

SMTP e PostgreSQL não compartilham transação. Se o servidor aceitar o e-mail e
o serviço cair ou o banco falhar antes de confirmar o marcador, a próxima
entrega fará outro envio. Com vários endereços, uma falha parcial pode repetir
os destinatários já atendidos. O lock evita execução simultânea cooperativa,
mas não transforma SMTP em entrega exatamente uma vez. Registrar sucesso antes
do envio poderia perder notificações e contraria RN-55.

## Atualização e verificação

Não há mudança de schema. Reconstruir a imagem incorpora o coordenador e a
configuração de ACK manual, ainda sem habilitar listeners. Futuras ações devem
usar o coordenador; as restrições únicas sozinhas não protegem efeitos SMTP.

Comandos e contrato de uso estão no [README](../../README.md).
Resultados em [docs/testes](../testes/ms-not-05-idempotencia-concorrencia.md).
