# ms-notificacoes

Serviço independente de notificações do FlowSLA. O app-principal publica eventos no RabbitMQ; futuramente este serviço consumirá as filas de e-mail e de notificações internas, usando seu próprio banco PostgreSQL. A API será consumida pelo frontend. Os serviços não compartilham banco nem consultam a API um do outro para compor notificações.

## Requisitos e execução

- Java 21; o Maven Wrapper baixa o Maven quando necessário.
- PostgreSQL acessível com banco e usuário próprios para o serviço.
- Defina as variáveis de ambiente usando [.env.example](.env.example) como referência. O arquivo é apenas um exemplo: o Spring Boot não carrega `.env` automaticamente.

Execute `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). Use `./mvnw test` para compilar e executar os testes. A porta padrão é 8081 e o endpoint de saúde é `/actuator/health`; sua resposta reflete a disponibilidade das dependências monitoradas.

A base contém os pacotes `api`, `eventos`, `persistencia`, `integracoes` e `config`. O consumidor interno está ativo; e-mails e API de notificações pertencem às tasks posteriores em [tasks_ms_not.md](docs/tasks/tasks_ms_not.md). Somente a saúde está acessível por HTTP: os demais caminhos são negados até a implementação da autenticação e autorização na MS-NOT-10. As filas são criadas pelo ambiente Docker e o schema pelo Flyway.

Os [contratos dos eventos](docs/contratos/eventos.md) descrevem os nove tipos, canais, destinatários e exemplos JSON válidos e inválidos. A MS-NOT-02 adiciona DTOs e o `LeitorEventoNotificacao` para desserialização e validação antes do processamento; o formato ainda precisa ser adotado pelo publicador do app-principal.

Não versione credenciais. O RabbitMQ e o SMTP são configurados por variáveis de ambiente; a topologia está em [topologia.json](docs/topologia.json) e o fluxo em [flowSLA.md](docs/flowSLA.md).

## Ambiente Docker — MS-NOT-03

Requer Docker com containers Linux e Docker Compose v2 ou superior. Não é necessário instalar Java/Maven no host para construir a imagem. Na raiz deste projeto:

```powershell
Copy-Item .env.example .env
# Edite .env: defina DB_USERNAME, DB_PASSWORD, RABBITMQ_USERNAME e RABBITMQ_PASSWORD.
docker compose up -d --build --wait
docker compose ps
docker compose port ms-notificacoes 8081
```

No Linux/macOS, use `cp .env.example .env`. Se `.env` já existir, ajuste-o sem sobrescrever suas credenciais. O Compose carrega esse arquivo; a execução direta do Spring continua exigindo variáveis exportadas no processo. A imagem é construída em duas etapas com Java 21, executa `mvn verify` e inicia o JAR como usuário sem privilégios. `.dockerignore` impede o envio de `.env`, `.git` e arquivos locais ao build.

| Serviço | Acesso pelo host (padrão) | Endereço dentro da rede Compose |
|---|---|---|
| ms-notificacoes | Porta dinâmica: consulte `docker compose port ms-notificacoes 8081`; saúde em `/actuator/health` | `ms-notificacoes:8081` |
| PostgreSQL | `localhost:15432`, banco `ms_notificacoes` | `postgres:5432` |
| RabbitMQ AMQP | `localhost:5673` | `rabbitmq:5672` |
| RabbitMQ Management | `http://localhost:15673`, credenciais `RABBITMQ_*` do `.env` | `rabbitmq:15672` |
| Mailpit SMTP / interface | `localhost:11025` / `http://localhost:18025` | `mailpit:1025` / `mailpit:8025` |

As portas são publicadas apenas em `127.0.0.1`. Altere `POSTGRES_LOCAL_PORT`, `RABBITMQ_LOCAL_PORT`, `RABBITMQ_MANAGEMENT_LOCAL_PORT`, `MAIL_SMTP_LOCAL_PORT` e `MAIL_UI_LOCAL_PORT` para evitar conflitos. O Compose define os endereços internos do serviço; `DB_URL`, `RABBITMQ_HOST/PORT` e `MAIL_HOST/PORT` do `.env.example` servem para execução Java no host. O microsserviço não tem `container_name` nem porta fixa no host e pode ser escalado:

```powershell
docker compose up -d --scale ms-notificacoes=4 --wait
docker compose port --index 1 ms-notificacoes 8081
docker compose port --index 4 ms-notificacoes 8081
```

Cada projeto Compose recebe rede e volumes próprios. Não há mounts ou dependências do repositório `backend`. Esta stack é independente da infraestrutura do app-principal: para integração futura, o produtor deve conectar ao broker deste ambiente ou os dois serviços devem compartilhar um broker/rede configurado explicitamente.

O serviço aguarda PostgreSQL, RabbitMQ e Mailpit saudáveis. O `rabbitmq-init` termina após gerar, em volume Docker, as definições com hash de senha e vhost do ambiente; o broker as importa no boot. Nenhuma credencial gerada vai para o checkout. O usuário RabbitMQ tem acesso administrativo para desenvolvimento e inspeção da topologia.

### Filas e persistência

`docs/topologia.json` é a fonte das definições de notificações; filas de evidências pertencem ao app-principal e não são criadas aqui.

| Exchange direta | Routing key | Fila durável | Destino de rejeição sem requeue |
|---|---|---|---|
| `notificacoes` | `email` | `notificacoes.email` | `notificacoes.email.dlq` |
| `notificacoes` | `interna` | `notificacoes.interna` | `notificacoes.interna.dlq` |

`notificacoes.dlx` usa as mesmas routing keys para as duas DLQs duráveis. Cada DLQ tem `x-message-ttl=604800000` (sete dias); filas de trabalho não têm TTL. A expiração elimina a mensagem da DLQ. São filas classic em um broker único, com armazenamento em volume e nome de nó estável; não fornecem replicação/alta disponibilidade.

**O produtor precisa publicar com `delivery_mode=2` (persistente), publisher confirms e tratamento de mensagens não roteadas.** Fila durável sozinha não torna a mensagem persistente. Este repositório não implementa o publicador do app-principal. A separação das filas prepara o isolamento dos canais; consumidores, retries e encaminhamento definitivo para DLQ serão implementados nas MS-NOT-06 a MS-NOT-09.

Volumes `postgres-data` e `rabbitmq-data` preservam os dados quando containers são reiniciados ou recriados. `rabbitmq-definitions` contém as definições geradas. `docker compose down` preserva os volumes; **`down -v` apaga os dados**. O Mailpit é uma caixa de teste efêmera, sem volume. Alterar a senha do PostgreSQL no `.env` não altera o usuário de um banco já inicializado. Alterar argumentos imutáveis de filas exige migração planejada; importar definições não modifica automaticamente filas existentes.

### Validação reproduzível

Use uma stack descartável exclusiva (Python 3 no host). O script reinicia e recria **somente o RabbitMQ de `ms-not-03-test`**, consome suas mensagens de teste e escala o microsserviço para quatro instâncias. Ele recusa filas que já contenham mensagens. Pare outra stack deste projeto ou escolha portas distintas no `.env` antes de executar:

```powershell
docker compose -p ms-not-03-test up -d --build --wait --wait-timeout 240
python infra/smoke_test.py
docker compose -p ms-not-03-test down
```

O script desativa o consumidor interno somente na stack descartável `ms-not-03-test` para observar mensagens pendentes durante os testes de infraestrutura. Ele confere topologia/TTL de sete dias, roteamento dos dois canais, mensagens persistentes após restart e recriação, rejeição para cada DLQ, acesso ao banco, captura SMTP no Mailpit e saúde das quatro réplicas. A retenção de sete dias é conferida na configuração efetiva do broker; o teste não espera sete dias. O build roda também a suíte Java. Resultados desta implementação estão em [docs/testes/ms-not-03-docker-rabbitmq.md](docs/testes/ms-not-03-docker-rabbitmq.md).

Referências: [importação de definições RabbitMQ](https://www.rabbitmq.com/docs/definitions), [durabilidade e confirmação de publicação](https://www.rabbitmq.com/docs/confirms), [health checks Mailpit](https://mailpit.axllent.org/docs/integration/healthcheck/) e [rede/escala no Compose](https://docs.docker.com/compose/how-tos/networking/).

## Banco e migrations — MS-NOT-04

O serviço usa o banco e as credenciais `DB_*` próprios, sem acessar o banco do backend. O Flyway aplica as migrations de [`src/main/resources/db/migration`](src/main/resources/db/migration) antes da inicialização JPA. O Hibernate usa `ddl-auto=validate`: valida o mapeamento, sem criar ou alterar tabelas. O Flyway valida checksums e tem `clean` desabilitado.

| Tabela | Conteúdo e unicidade |
|---|---|
| `notificacao_interna` | UUID local, `destinatario_sub`, `evento_id`, tipo interno, ID/número/título do chamado, `criado_em` e `lida_em` opcional. Única por `(evento_id, destinatario_sub)` |
| `evento_processado` | UUID local, `evento_id`, canal e instante do sucesso. Única por `(evento_id, canal)` |

Os identificadores recebidos são strings opacas, inclusive o `eventoId` determinístico de SLA; somente os IDs locais são UUIDs gerados pelo JPA. Datas usam `Instant`/`timestamp with time zone` com precisão de microssegundos. Novas notificações têm leitura nula. Campos do evento são imutáveis no mapeamento JPA; as datas são fornecidas pelo chamador, que deverá obter o instante de um `Clock` ao implementar o processamento.

Os índices atendem consulta por destinatário com ordenação `criado_em DESC, id DESC`, contagem de não lidas (índice parcial) e futuro expurgo por data de criação. O banco rejeita duplicações, campos obrigatórios nulos/vazios, tipos de e-mail em notificações internas, canais desconhecidos e leitura anterior à criação.

Não há chave estrangeira entre as duas tabelas: apagar uma notificação não apaga seu marcador de sucesso. Também não há colunas para e-mail, texto de posicionamento ou payload. O marcador representa sucesso do **evento inteiro no canal**, incluindo todos os destinatários internos. A coordenação concorrente é feita pelo processador da MS-NOT-05, descrito abaixo. O expurgo automático de 90 dias pertence à MS-NOT-12.

Para atualizar uma instalação existente, execute `docker compose up -d --build --wait`. A V1 cria as tabelas num banco vazio, inclusive no banco provisionado pela MS-NOT-03 que ainda não tenha tabelas. Não habilite `baseline-on-migrate` nem apague dados para contornar divergências em um schema já preenchido. Depois de aplicada, uma migration deve permanecer imutável; alterações futuras entram em V2, V3 etc.

### Testes PostgreSQL

`mvnw.cmd test` executa contratos e inicialização com H2, sem aplicar o SQL PostgreSQL. Para testar migrations, restrições, índices e repositórios reais, use o perfil `postgres-it`. O teste cria um schema aleatório `it_*` vazio e usa `ddl-auto=validate`; não altera tabelas de outros schemas. O schema de testes fica no banco de testes para inspeção.

Com Docker e `.env` configurado, na raiz do projeto, execute em um projeto separado:

```powershell
# Evita conflito com a stack principal que já usa localhost:15432.
$env:POSTGRES_LOCAL_PORT = '25432'
docker compose -p ms-not-04-test -f compose.yaml -f infra/compose.postgres-it.yaml run --build --rm postgres-it
docker compose -p ms-not-04-test -f compose.yaml -f infra/compose.postgres-it.yaml down
Remove-Item Env:POSTGRES_LOCAL_PORT
```

Esse comando inicia somente PostgreSQL e o runner de testes; não requer Java/Python no host. O runner mantém um volume de cache Maven. Para executar com Java 21 no host, defina `IT_DB_URL`, `IT_DB_USERNAME` e `IT_DB_PASSWORD` apontando a um banco PostgreSQL de testes e rode `mvnw.cmd -Ppostgres-it verify` (Linux/macOS: `./mvnw -Ppostgres-it verify`). O perfil falha se o banco não estiver disponível; não substitui PostgreSQL por H2.

Detalhes e resultados: [implementação](docs/tasks/ms-not-04-banco-flyway.md) e [testes](docs/testes/ms-not-04-banco-flyway.md). Referência de configuração: [inicialização de banco no Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

## Idempotência e concorrência — MS-NOT-05

`ProcessadorEventoIdempotente.processar(eventoId, canal, acao)` coordena o processamento com um advisory lock **transacional** do PostgreSQL por evento/canal. A ordem é: adquirir lock, consultar sucesso, executar a ação síncrona, inserir o marcador de sucesso e confirmar a transação. Se já houver sucesso, retorna `JA_PROCESSADO` sem chamar a ação. Caso contrário, retorna `PROCESSADO` somente depois do commit.

Cada chamada usa uma transação nova `READ_COMMITTED`. Isso garante que a consulta após esperar o lock veja o commit da instância anterior e que o retorno represente commit concluído, mesmo se o chamador tiver uma transação externa. A ação deve usar os repositórios no mesmo thread e participar dessa transação (`REQUIRED`), propagando falhas; não deve abrir transações independentes ou executar efeitos assíncronos. Assim, todas as notificações dos destinatários e o marcador são confirmados juntos. As restrições únicas da V1 continuam protegendo evento/destinatário e evento/canal.

O lock é liberado automaticamente no commit/rollback. `PROCESSAMENTO_LOCK_TIMEOUT` limita a espera (padrão `10s`, sintaxe PostgreSQL); timeout/falha não grava sucesso. A chave usa hash de 64 bits da identidade completa com prefixo do serviço. Uma colisão de hash apenas serializa eventos distintos: a consulta de sucesso continua usando evento/canal completos. Eventos diferentes e canais diferentes podem processar em paralelo.

Os consumidores chamam `ProcessadorMensagemNotificacao.processar(mensagem, channel, canal, acao)`. O adaptador valida o contrato/canal, chama o coordenador e executa `basicAck(deliveryTag, false)` no canal de origem depois do commit, inclusive nas repetições já processadas. Os containers Spring AMQP simple/direct usam ACK manual. O consumidor interno está conectado na MS-NOT-06; e-mails serão conectados na MS-NOT-07.

Falhas de validação, ação, lock, persistência ou commit são propagadas pelo adaptador **sem ACK**. O listener interno rejeita mensagens inválidas sem requeue (DLQ) e solicita reentrega nas falhas de processamento. A política com classificação de falhas, limite e backoff será implementada na MS-NOT-08. Uma falha no próprio ACK também é propagada: se o broker reentregar após o commit, o marcador evita repetir a ação. Não há ACK antecipado nem confirmação em lote.

O app-principal deve preservar `eventoId` **e o conjunto de destinatários** nas republicações. Todos os destinatários internos fazem parte do mesmo processamento; uma notificação por evento/sub é protegida no banco. Uma mensagem que reutilize um ID já concluído com destinatários diferentes será reconhecida como repetição. Os marcadores não são removidos quando notificações são excluídas.

**Limite SMTP:** o envio de e-mail não participa da transação PostgreSQL. Se o SMTP aceitar a mensagem e a aplicação cair ou o commit falhar antes de registrar o sucesso, a reentrega poderá enviar o e-mail novamente. O lock impede envios simultâneos pelo mesmo evento/canal, mas não elimina essa janela. Gravar o sucesso antes do envio criaria risco de perda e viola RN-55. Não há promessa de entrega exatamente uma vez por SMTP; em eventos com vários endereços, uma falha parcial também pode repetir os envios já aceitos. E-mail e texto do posicionamento não são gravados no banco ou em logs pelo coordenador.

### Testes com PostgreSQL e RabbitMQ

O perfil `postgres-it` continua executando os testes de banco, agora incluindo concorrência. O perfil **`mensageria-it`** executa esses testes e a suíte de ACK/reentrega com RabbitMQ real (não combine os dois perfis). Com `.env` configurado:

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
docker compose -p ms-not-05-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it
docker compose -p ms-not-05-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml down
Remove-Item Env:POSTGRES_LOCAL_PORT, Env:RABBITMQ_LOCAL_PORT, Env:RABBITMQ_MANAGEMENT_LOCAL_PORT
```

O runner inicia somente PostgreSQL, RabbitMQ e seu init, usa schemas aleatórios e filas temporárias exclusivas e preserva os volumes ao encerrar. No host, o perfil `mensageria-it` requer as variáveis `IT_DB_*` descritas acima e `IT_RABBITMQ_HOST`, `IT_RABBITMQ_USERNAME`, `IT_RABBITMQ_PASSWORD`, com `IT_RABBITMQ_PORT`/`IT_RABBITMQ_VHOST` opcionais (padrões `5672`/`/`).

Detalhes: [implementação](docs/tasks/ms-not-05-idempotencia-concorrencia.md) e [evidências](docs/testes/ms-not-05-idempotencia-concorrencia.md). Referências: [locks transacionais PostgreSQL](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS) e [confirmações RabbitMQ](https://www.rabbitmq.com/docs/confirms).

## Consumo interno — MS-NOT-06

`ConsumidorNotificacaoInterna` inicia automaticamente na fila `notificacoes.interna`. Ele valida o payload e o canal pelo leitor da MS-NOT-02 e executa `PersistidorNotificacaoInterna` dentro do coordenador da MS-NOT-05. A transação grava uma notificação por `sub` distinto recebido e um único marcador de sucesso do evento/canal. Todos os destinatários recebem o mesmo instante de criação, obtido do `Clock`, e leitura inicial nula. O ACK individual ocorre depois do commit. Republicações não recriam notificações, inclusive após sua exclusão.

| Tipo | Mensagem em texto simples |
|---|---|
| `ATRIBUICAO` | Você foi atribuído ao chamado {número} — {título}. |
| `REATRIBUICAO` | O chamado {número} — {título} foi reatribuído a você. |
| `REMOCAO` | Você foi removido do chamado {número} — {título}. |
| `REABERTURA` | O chamado {número} — {título} foi reaberto. |
| `CONCLUSAO` | O atendimento do chamado {número} — {título} foi concluído. |
| `VIOLACAO_SLA` | O chamado {número} — {título} teve um prazo de SLA violado. |

O texto é calculado por `NotificacaoInterna.getMensagem()` com os campos persistidos (`@Transient`); não há coluna redundante nem alteração na V1. O título original é preservado. A futura API poderá expor esse texto; o frontend deverá tratá-lo como texto, sem interpretar HTML fornecido no título.

O consumidor não consulta backend, Keycloak ou SMTP. O produtor é responsável por selecionar gestores/técnicos conforme as regras e, na inativação, enviar somente o substituto em `REATRIBUICAO`, sem emitir `REMOCAO` para o inativado. Uma notificação de remoção mantém a referência do chamado para identificação e não concede acesso a ele; a autorização continua responsabilidade do app-principal. Destinatários repetidos na mesma lista são consolidados pelo `sub`, sem normalizar ou modificar identificadores opacos.

Mensagens inválidas, desconhecidas ou de e-mail recebidas na fila interna são rejeitadas com `requeue=false`, seguindo a DLX configurada no broker. Falhas de processamento/banco provocam rollback e NACK individual com `requeue=true`. O tratamento atual não tem limite de tentativas nem backoff: uma falha persistente pode causar reentregas contínuas até a implementação da MS-NOT-08. Falhas de I/O no ACK são propagadas para recuperação do canal; o marcador já confirmado protege a próxima entrega. Os logs do listener não incluem corpo, e-mail, posicionamento ou stack trace contendo payload.

Variáveis novas:

- `CONSUMIDOR_INTERNO_ENABLED` (padrão `true`): permite desativar o listener, por exemplo em testes de infraestrutura.
- `NOTIFICACOES_FILA_INTERNA` (padrão `notificacoes.interna`): permite apontar para fila equivalente já provisionada; alterar esse valor não cria automaticamente filas/bindings.

Para aplicar, use `docker compose up -d --build --wait`. Isso habilita o consumidor interno na stack; a fila de e-mail continua sem consumidor até a MS-NOT-07. Nenhum ajuste no backend ou no modo de integração entre os dois Composes faz parte desta task.

A suíte `mensageria-it` agora inicia o listener Spring real em filas exclusivas dos testes, com três consumidores, além dos testes anteriores. O perfil `postgres-it` e o teste H2 deixam o listener desligado. Para repetir a validação, use os comandos de mensageria acima trocando o projeto por `ms-not-06-test`.

Detalhes: [implementação](docs/tasks/ms-not-06-consumo-interno.md) e [testes](docs/testes/ms-not-06-consumo-interno.md).
