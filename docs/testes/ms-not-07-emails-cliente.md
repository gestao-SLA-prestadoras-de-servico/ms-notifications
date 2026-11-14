# MS-NOT-07 — Evidências dos e-mails ao cliente

Data local: 12/11/2026. Branch: `feature/ms-not-7-email-cliente`.
Ambiente: Java 21, PostgreSQL 17.11, RabbitMQ 4.1 e Mailpit 1.27 em Docker.
Stack isolada: `ms-not-07-test`, com schemas e filas exclusivos dos testes.

## Execução

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'

docker compose -p ms-not-07-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it
docker compose -p ms-not-07-test -f compose.yaml -f infra/compose.postgres-it.yaml run --rm postgres-it

# Imagem final e filas padrão:
docker compose -p ms-not-07-test up -d --build --wait --wait-timeout 240
```

A suíte de mensageria usa envio SMTP real para Mailpit e sua API HTTP somente
para leitura das mensagens capturadas. A caixa não é apagada globalmente entre
testes: cada caso é identificado por título/endereço fictício único. O teste de
indisponibilidade troca temporariamente a porta SMTP do cliente para a porta 1
do container Mailpit (sem servidor SMTP), gerando recusa real de conexão.

## Resultados

| Verificação | Resultado observado | Status |
|---|---|---|
| Testes locais | 66 testes, 0 falhas/erros/ignorados | Passou |
| Integração completa | 44 testes, 0 falhas/erros/ignorados; `BUILD SUCCESS` | Passou |
| Perfil PostgreSQL sem mensageria | 66 testes locais + 18 de banco; listeners desligados e suítes RabbitMQ/Mailpit excluídas; `BUILD SUCCESS` | Passou |
| Três eventos SMTP | Primeiro posicionamento, resolução e aviso de encerramento recebidos no Mailpit | Passou |
| Assunto/dados | Número e título preservados no assunto e nas versões texto/HTML; conteúdo próprio por evento | Passou |
| Posicionamento do gestor | Texto recebido presente nas versões texto e HTML | Passou |
| Destinatário/remetente | Cabeçalhos `To` e `From` conferidos pela API Mailpit | Passou |
| Contatos múltiplos | Um envelope individual por endereço distinto; endereço repetido não gerou outro envio e cada `To` tinha apenas seu contato | Passou |
| Escape HTML | Tags `<img>` e `<script>`, aspas e ampersand foram escapados; posicionamento manteve quebras como `<br>` | Passou |
| Texto simples | Conteúdo original preservado, inclusive os caracteres que foram escapados no HTML | Passou |
| Proteção do assunto | CR/LF removidos em teste de tentativa de inserir cabeçalhos adicionais | Passou |
| Reentregas concorrentes | 25 publicações do mesmo evento com três consumidores geraram um único e-mail e um marcador | Passou |
| Contrato/canal inválidos | Primeiro posicionamento sem texto, endereço inválido e evento interno na fila de e-mail foram rejeitados para DLQ sem envio SMTP nem sucesso | Passou |
| Falha SMTP | Conexão recusada não gravou marcador de sucesso; entrega permaneceu para requeue | Passou |
| Isolamento | Durante a recusa SMTP, o listener interno gravou sua notificação e marcador normalmente | Passou |
| Recuperação SMTP | Após restaurar o endpoint, a mensagem pendente foi enviada e recebeu marcador EMAIL | Passou |
| Minimização | Fluxo de e-mail não criou notificação interna nem coluna nova; `Conteudo.toString()` não expôs título/posicionamento | Passou |
| Imagem final/fila padrão | Os dois listeners estavam ativos; seis publicações (duas por tipo de e-mail) produziram três mensagens SMTP e três marcadores EMAIL; evento interno também foi persistido; saúde `UP` | Passou |
| Smoke de infraestrutura | Com ambos os consumidores desativados somente na stack `ms-not-03-test`, passaram roteamento, persistência após restart/recriação do RabbitMQ, DLQs, PostgreSQL, SMTP Mailpit e quatro instâncias saudáveis | Passou |

Total da suíte completa: **110 testes distintos**, todos aprovados: 66 locais
e 44 de integração. Os dez testes novos de integração de e-mail foram executados
em `ConsumoEmailMailpitIT`; os demais 34 testes de integração permaneceram
aprovados. A falha de conexão e os NACKs presentes nos logs são cenários negativos
esperados, não testes com falha.

No teste da imagem final, foram publicados exemplos fictícios na exchange
`notificacoes`, routing key `email`, com `delivery_mode=2`. Os IDs eram únicos e
cada evento foi publicado duas vezes. O Mailpit confirmou número, título,
destinatário/remetente, corpo texto/HTML e escape; o PostgreSQL confirmou os
marcadores. Uma publicação adicional em `interna` confirmou que os dois canais
estavam conectados na imagem final. Nenhum evento foi produzido pelo backend.

Após encerrar `ms-not-07-test`, o smoke foi repetido na stack descartável da
MS-NOT-03, usando as mesmas portas alternativas e as variáveis
`CONSUMIDOR_INTERNO_ENABLED=false`/`CONSUMIDOR_EMAIL_ENABLED=false`:

```powershell
docker compose -p ms-not-03-test up -d --build --wait --wait-timeout 240
python infra/smoke_test.py
docker compose -p ms-not-03-test down
```

As duas stacks de teste foram encerradas sem apagar volumes. O Mailpit de teste
é efêmero e suas mensagens não são mantidas após remover o container. A stack
principal permaneceu em execução e não foi reiniciada.

## Limites

- Não foram testados autenticação/TLS de um provedor SMTP externo; Mailpit foi usado sem autenticação/TLS, como no Compose de desenvolvimento.
- A indisponibilidade foi uma recusa real de conexão em porta sem SMTP. Classificação SMTP 4xx/5xx, retries limitados, backoff e circuit breaker pertencem às MS-NOT-08/MS-NOT-09.
- O envio SMTP pode ser repetido se for aceito antes de falha no commit/queda do serviço ou em envio parcial a vários contatos; o marcador não elimina essa janela.
- O produtor é responsável pelo contato e por emitir o aviso um dia útil antes do encerramento. Não foi validada a outbox do backend ou o alinhamento de infraestrutura entre os projetos.
- Nenhuma migration, arquivo do backend ou dado da stack principal foi alterado. Os testes usam somente destinatários fictícios e SMTP local capturado no Mailpit.

Configuração e roteiro: [README](../../README.md).
