# MS-NOT-03 — Evidências de validação

Data: 11/11/2026. Branch: `feature/ms-not-3`.
Ambiente: Docker Desktop Linux containers, Docker 29.9.2 e Compose 5.6.1.
Projeto de testes exclusivo: `ms-not-03-test`.

## Execução

```powershell
docker compose -p ms-not-03-test config --quiet
docker compose -p ms-not-03-test up -d --build --wait --wait-timeout 240
python infra/smoke_test.py
docker compose -p ms-not-03-test exec -T ms-notificacoes id
docker compose -p ms-not-03-test exec -T ms-notificacoes java -version
docker compose -p ms-not-03-test down
```

Credenciais locais aleatórias foram geradas em `.env` ignorado pelo Git;
nenhuma senha foi incluída nas evidências ou na imagem.

| Verificação | Resultado observado | Status |
|---|---|---|
| Configuração Compose | `config --quiet` terminou com código 0 | Passou |
| Build com suíte Java | `mvn verify`: 32 testes, 0 falhas, 0 erros, 0 ignorados; `BUILD SUCCESS` | Passou |
| Inicialização com dependências reais | Microsserviço, PostgreSQL, RabbitMQ e Mailpit saudáveis; init terminou com código 0 | Passou |
| Exchange e filas | Duas exchanges direct duráveis; quatro filas duráveis e sem auto-delete; argumentos efetivos iguais ao JSON versionado | Passou |
| Isolamento de roteamento | Payload `RESOLUCAO` na fila email e `ATRIBUICAO` na fila interna; IDs de teste distintos preservados | Passou |
| Persistência da mensagem | API retornou `delivery_mode=2`; payloads recuperados após `restart rabbitmq` | Passou |
| Persistência do volume | Payloads recuperados após `up --no-deps --force-recreate --wait rabbitmq`, mantendo volume e nome do nó | Passou |
| Dead lettering | Rejeição `reject_requeue_false` enviou cada payload à sua DLQ com `x-death.reason=rejected`; filas de origem ficaram vazias | Passou |
| Retenção das DLQs | Broker retornou `x-message-ttl=604800000` nas duas DLQs | Passou |
| PostgreSQL | Consulta `current_database()` com usuário configurado retornou `ms_notificacoes` | Passou |
| Mailpit | Mensagem enviada por SMTP; API `/api/v1/messages` confirmou captura | Passou |
| Escala | Quatro containers saudáveis; `/actuator/health` retornou `UP` nas quatro portas publicadas | Passou |
| Runtime sem root | `uid=100(notificacoes) gid=101(notificacoes)` | Passou |
| Java do runtime | Temurin OpenJDK `21.0.13+9-LTS` | Passou |
| Encerramento | `down` removeu os containers e a rede de testes; volumes preservados | Passou |

As portas HTTP observadas nas quatro instâncias foram `50984`, `60293`, `60297`
e `60298`, todas em `127.0.0.1` e com destino interno `8081`. São atribuídas
dinamicamente e mudarão em uma próxima execução.

## Limites da validação

- O TTL de sete dias foi conferido na configuração efetiva, sem aguardar sete dias.
- A persistência foi testada em reinício e recriação de container; não foi simulada perda de disco, host ou queda abrupta de energia.
- As mensagens de teste foram publicadas pela API de desenvolvimento do RabbitMQ. O publicador real do backend, seus confirms e sua outbox não foram testados ou modificados.
- A rejeição para DLQ foi exercitada diretamente no broker; não há consumidores/retries nesta task.
- As quatro réplicas tiveram sua saúde verificada; não foi realizado teste de vazão/escalabilidade da MS-NOT-16.
- Não foram alterados arquivos do backend. A consulta de status somente leitura encontrou alterações já presentes em `docs/flowSLA.md`; elas foram preservadas.

O roteiro automatizado está em [infra/smoke_test.py](../../infra/smoke_test.py).
