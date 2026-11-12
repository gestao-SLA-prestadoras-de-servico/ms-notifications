# MS-NOT-06 — Consumir e persistir notificações internas

Requisitos: RF-53 a RF-55, RF-57/RF-58; RN-35 a RN-41, RN-53/RN-58.
A RN-135 orienta os destinatários emitidos pelo produtor na transferência.
Dependências: MS-NOT-03 e MS-NOT-05.

| Critério / passo | Solução |
|---|---|
| Consumir os seis tipos internos | `ConsumidorNotificacaoInterna`, listener `notificacoesInternas`, com fila padrão `notificacoes.interna` e ACK manual |
| Uma notificação por destinatário | `PersistidorNotificacaoInterna` mapeia cada `sub` distinto para uma entidade; repetições na lista são consolidadas |
| Não lida e vinculada ao chamado | `lida_em` nula, ID/número/título/tipo recebidos preservados, criação via `Clock` |
| Autonomia | Não há consulta ao app-principal, usuários, contratos, preferências ou SMTP |
| Composição de mensagens | `NotificacaoInterna.getMensagem()` compõe os seis textos com tipo/número/título persistidos |
| Persistência transacional | Persistidor com propagação `MANDATORY` participa da transação da MS-NOT-05; conjunto e marcador são confirmados juntos |
| Duplicações | Reuso do lock por evento/canal, marcador de sucesso e unicidade evento/sub da V1; ACK apenas após retorno do coordenador |

## Decisões

- A composição é derivada, em texto simples, sem nova coluna/migration. Isso aproveita os campos definidos na MS-NOT-04 e preserva o título recebido. Mudanças futuras nos templates também se refletirão em notificações antigas ao consultá-las.
- Todos os destinatários do evento recebem um único instante de criação. O serviço exige uma transação existente para impedir persistência acidental fora da coordenação idempotente.
- O consumidor aceita somente tipos internos pelo leitor de contrato. Ele não aplica filtros de destinatários adicionais: seleção de gestores, técnico anterior, substituto e preferências pertence ao produtor.
- Invalidade de contrato/canal gera rejeição sem requeue; a DLX da MS-NOT-03 encaminha à DLQ. O listener faz isso diretamente, sem propagar o payload ao logger de erros do container.
- Falha na ação/banco resulta em NACK com requeue depois do rollback, preservando a mensagem. Limite, classificação e backoff continuam para MS-NOT-08; o tratamento atual pode repetir continuamente falhas persistentes.
- Erros de I/O no ACK não são convertidos em sucesso. O broker poderá reentregar; o marcador protege os efeitos já confirmados.
- `CONSUMIDOR_INTERNO_ENABLED` liga/desliga o listener; `NOTIFICACOES_FILA_INTERNA` permite filas de teste ou implantação equivalente. A topologia continua provisionada externamente.
- Os contextos de teste anteriores deixam esse listener desligado. A suíte nova de mensageria liga o listener real, com três consumidores, em filas e schema exclusivos.
- O smoke test de infraestrutura desativa o listener somente no projeto descartável `ms-not-03-test`, para que os testes de persistência do broker continuem observando mensagens pendentes.

## Atualização

Reconstruir/iniciar a imagem habilita o consumidor na fila interna. Os eventos
pendentes válidos passarão a ser persistidos; inválidos irão à DLQ configurada.
Não há alteração de schema ou credenciais. E-mails continuam para MS-NOT-07,
e a API de leitura é responsabilidade da MS-NOT-11.

Nenhum arquivo do backend foi alterado. Esta implementação não comprova o
publicador real do backend nem muda o isolamento dos Composes registrado na
auditoria da MS-NOT-05.

Verificação: [README](../../README.md) e [relatório de testes](../testes/ms-not-06-consumo-interno.md).
