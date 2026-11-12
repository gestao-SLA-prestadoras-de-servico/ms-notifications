# ms-notificacoes

Serviço independente de notificações do FlowSLA. Consome eventos de e-mail e de notificações internas no RabbitMQ, usando seu próprio banco PostgreSQL. A API de leitura será consumida pelo frontend. Os serviços não compartilham banco nem consultam a API um do outro para compor notificações. A conexão com o publicador do app-principal depende do alinhamento de contrato e infraestrutura entre os projetos.

## Requisitos e execução

- Java 21; o Maven Wrapper baixa o Maven quando necessário.
- PostgreSQL acessível com banco e usuário próprios para o serviço.
- Defina as variáveis de ambiente usando [.env.example](.env.example) como referência. O arquivo é apenas um exemplo: o Spring Boot não carrega `.env` automaticamente.

Execute `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). Use `./mvnw test` para compilar e executar os testes. A porta padrão é 8081 e o endpoint de saúde é `/actuator/health`; sua resposta reflete a disponibilidade das dependências monitoradas.

A base contém os pacotes `api`, `eventos`, `persistencia`, `integracoes` e `config`. Os dois consumidores e a API de consulta/leitura estão implementados. `/notificacoes` e seus subcaminhos exigem JWT e papel TECNICO/GESTOR; somente a saúde é pública, e as demais rotas continuam negadas. As filas são criadas pelo ambiente Docker e o schema pelo Flyway.

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

O script desativa os dois consumidores somente na stack descartável `ms-not-03-test` para observar mensagens pendentes durante os testes de infraestrutura. Ele confere topologia/TTL de sete dias, roteamento dos dois canais, mensagens persistentes após restart e recriação, rejeição para cada DLQ, acesso ao banco, captura SMTP no Mailpit e saúde das quatro réplicas. A retenção de sete dias é conferida na configuração efetiva do broker; o teste não espera sete dias. O build roda também a suíte Java. Resultados desta implementação estão em [docs/testes/ms-not-03-docker-rabbitmq.md](docs/testes/ms-not-03-docker-rabbitmq.md).

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

Os consumidores chamam `ProcessadorMensagemNotificacao.processar(mensagem, channel, canal, acao)`. O adaptador valida o contrato/canal, chama o coordenador e executa `basicAck(deliveryTag, false)` no canal de origem depois do commit, inclusive nas repetições já processadas. Os containers Spring AMQP simple/direct usam ACK manual. Os consumidores interno e de e-mail estão conectados desde as MS-NOT-06/MS-NOT-07.

Falhas de validação, ação, lock, persistência ou commit são propagadas pelo adaptador **sem ACK**. `ProcessadorEntrega` aplica a classificação e o limite da MS-NOT-08, publica na fila de espera ou DLQ com confirmação e rota válida e só então confirma a origem. Uma falha no próprio ACK é propagada: se o broker reentregar após o commit, o marcador evita repetir a ação. Não há ACK antecipado nem confirmação em lote.

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

O runner de mensageria inicia PostgreSQL, RabbitMQ, seu init e Mailpit, usa schemas aleatórios e filas temporárias exclusivas e preserva os volumes ao encerrar. No host, o perfil `mensageria-it` requer as variáveis `IT_DB_*` descritas acima, `IT_RABBITMQ_HOST`, `IT_RABBITMQ_USERNAME`, `IT_RABBITMQ_PASSWORD`, `IT_MAIL_HOST` e `IT_MAILPIT_URL`, com `IT_RABBITMQ_PORT`/`IT_RABBITMQ_VHOST`/`IT_MAIL_PORT` opcionais (padrões `5672`/`/`/`1025`).

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

Mensagens inválidas, desconhecidas ou de e-mail recebidas na fila interna seguem diretamente para a DLQ por publicação confirmada. Falhas temporárias de banco recebem até três retries com backoff; falhas definitivas e esgotamento seguem à DLQ. Falhas de I/O no ACK são propagadas para recuperação do canal; o marcador já confirmado protege a próxima entrega. Os logs não incluem corpo, e-mail, posicionamento ou stack trace contendo payload.

Variáveis novas:

- `CONSUMIDOR_INTERNO_ENABLED` (padrão `true`): permite desativar o listener, por exemplo em testes de infraestrutura.
- `NOTIFICACOES_FILA_INTERNA` (padrão `notificacoes.interna`): permite apontar para fila equivalente já provisionada; alterar esse valor não cria automaticamente filas/bindings.

Para aplicar, use `docker compose up -d --build --wait`. Os dois consumidores são habilitados por padrão; consulte abaixo as configurações de e-mail. Nenhum ajuste no backend ou no modo de integração entre os dois Composes faz parte desta task.

A suíte `mensageria-it` agora inicia o listener Spring real em filas exclusivas dos testes, com três consumidores, além dos testes anteriores. O perfil `postgres-it` e o teste H2 deixam o listener desligado. Para repetir a validação, use os comandos de mensageria acima trocando o projeto por `ms-not-06-test`.

Detalhes: [implementação](docs/tasks/ms-not-06-consumo-interno.md) e [testes](docs/testes/ms-not-06-consumo-interno.md).

## E-mails ao cliente — MS-NOT-07

`ConsumidorEmailCliente` inicia automaticamente na fila `notificacoes.email`, valida o contrato/canal e executa `EnviadorEmailCliente` dentro do coordenador idempotente. O serviço usa `JavaMailSender`, exige a transação existente e envia um envelope individual por endereço distinto recebido no evento. Não consulta o backend ou o cadastro de clientes. O produtor deve informar o e-mail de contato correto; destinatários internos não são convertidos em e-mail.

| Tipo | Assunto (seguido de número e título) | Conteúdo |
|---|---|---|
| `PRIMEIRO_POSICIONAMENTO` | `[FlowSLA] Primeiro posicionamento` | Posicionamento registrado pelo gestor, incluindo o texto obrigatório do evento |
| `RESOLUCAO` | `[FlowSLA] Chamado resolvido` | Informa a resolução e orienta validar ou reabrir o chamado no sistema |
| `AVISO_ENCERRAMENTO` | `[FlowSLA] Validação automática próxima` | Avisa a proximidade da validação/encerramento e orienta conferir a solução |

Os três e-mails incluem número e título do chamado em texto e HTML, com UTF-8 e alternativas MIME para os dois formatos. Número, título e posicionamento são escapados em HTML; quebras de linha do posicionamento viram `<br>`. Quebras CR/LF são removidas do assunto. Cada destinatário recebe somente seu próprio endereço no cabeçalho `To`. O aviso não calcula datas nem agenda o envio: o app-principal deve gerar o evento um dia útil antes do encerramento, conforme RN-34. Não se usa “amanhã” no conteúdo, pois a entrega pode ser atrasada na fila.

O marcador `EMAIL` é gravado somente após todos os envios terminarem, e o ACK vem após o commit. E-mail, corpo e texto do posicionamento não são persistidos; os logs do listener registram somente a categoria da falha, sem destinatário, payload ou mensagem de exceção SMTP. Republicações de eventos concluídos não reenviam. A janela SMTP entre aceitação e gravação do sucesso, descrita na MS-NOT-05, permanece: queda/falha no commit ou envio parcial pode produzir e-mail duplicado na próxima entrega.

Mensagens inválidas ou de outro canal e erros SMTP 5xx seguem diretamente para a DLQ por publicação confirmada. Respostas SMTP temporárias e falhas temporárias de banco recebem até três retries com backoff. A indisponibilidade do transporte SMTP abre o circuit breaker e pausa o consumidor de e-mail; o canal interno continua operacional.

| Variável | Padrão | Uso |
|---|---|---|
| `MAIL_FROM` | `notificacoes@flowsla.local` | Remetente local de desenvolvimento; configure um remetente autorizado ao usar provedor real |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `1025` na aplicação | Endpoint SMTP; no Compose o serviço usa `mailpit:1025` |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | vazios | Credenciais SMTP, fora do versionamento |
| `MAIL_SMTP_AUTH` | `false` | Habilita autenticação SMTP |
| `MAIL_STARTTLS_ENABLED` | `false` | Habilita e exige STARTTLS; a conexão falha se o servidor não suportar TLS |
| `MAIL_CONNECTION_TIMEOUT_MS` | `5000` | Timeout de conexão em milissegundos |
| `MAIL_READ_TIMEOUT_MS` | `5000` | Timeout de leitura em milissegundos |
| `MAIL_WRITE_TIMEOUT_MS` | `5000` | Timeout de escrita em milissegundos |
| `CONSUMIDOR_EMAIL_ENABLED` | `true` | Liga/desliga o listener de e-mail |
| `NOTIFICACOES_FILA_EMAIL` | `notificacoes.email` | Fila equivalente previamente provisionada |

Para desenvolvimento, execute `docker compose up -d --build --wait` e abra o [Mailpit local](http://localhost:18025) após publicar um dos exemplos válidos na exchange `notificacoes`, routing key `email`, com mensagem persistente. O SMTP local do Mailpit não exige autenticação/TLS. O Compose mantém o endpoint interno do Mailpit; para apontar a imagem a um provedor externo, sobrescreva `MAIL_HOST`/`MAIL_PORT` no ambiente do serviço.

O perfil `mensageria-it` inclui a suíte `ConsumoEmailMailpitIT`, que usa listeners Spring reais, SMTP real e a API do Mailpit para verificar remetente/destinatários, conteúdo MIME, escape HTML, duplicações, falhas e isolamento. Use os comandos de mensageria acima com o projeto `ms-not-07-test` e, se houver outra stack ativa, defina também `MAIL_SMTP_LOCAL_PORT=21025` e `MAIL_UI_LOCAL_PORT=28025`. O perfil `postgres-it` continua sem depender de RabbitMQ/Mailpit. O smoke de infraestrutura desliga os dois consumidores somente em sua stack descartável.

Detalhes: [implementação](docs/tasks/ms-not-07-emails-cliente.md) e [testes](docs/testes/ms-not-07-emails-cliente.md). Referências: [alternativas texto/HTML com MimeMessageHelper](https://docs.spring.io/spring-framework/docs/6.2.7/javadoc-api/org/springframework/mail/javamail/MimeMessageHelper.html) e [API do Mailpit](https://mailpit.axllent.org/docs/api-v1/).

## Retries e DLQ — MS-NOT-08

Os dois listeners delegam a `ProcessadorEntrega`. Cada entrega executa a ação uma vez; em erro, `ClassificadorFalha` percorre causas, exceções encadeadas de JavaMail/SQL e falhas individuais de envio para decidir entre retry e DLQ. Não há `Thread.sleep`, loop de tentativas na aplicação ou retry adicional de `RabbitTemplate`/containers Spring AMQP.

| Falha | Tratamento |
|---|---|
| Timeout, conexão SMTP interrompida/recusada/DNS, SMTP 4xx | Inicial + até 3 retries |
| Falha temporária Spring Data/SQL (conexão, serialização/deadlock, recursos, lock timeout, indisponibilidade) | Inicial + até 3 retries |
| SMTP 5xx, contrato/canal/header inválido, preparação inválida, integridade SQL ou falha desconhecida | DLQ direta |
| Quarta tentativa temporária sem sucesso | DLQ do canal, com registro de falha nos logs |
| Falha ao publicar/confirmar ou ausência de rota | Sem ACK da origem; NACK com requeue para preservar a entrega |

SMTP 5xx prevalece quando um lote tem erros mistos. Códigos vêm das exceções do provider Angus; nas respostas de saudação/autenticação sem campo próprio, também é reconhecido o formato de resposta SMTP do provider. Mensagens de exceção, destinatários e corpo não são enviados aos logs. A dependência Angus já usada pelo starter em runtime passa também ao classpath de compilação para ler seus códigos tipados.

### Filas de espera e backoff

`notificacoes.retry` é uma exchange direct durável, com seis filas quorum duráveis: `notificacoes.retry.{email|interna}.{1|2|3}`. A routing key é `{canal}.{etapa}`. Políticas por etapa definem TTL, `dead-letter-strategy=at-least-once` e `overflow=reject-publish`. Ao expirar, a mensagem volta à exchange `notificacoes`, routing key do canal. O broker conserva a cópia na fila de espera até a fila de trabalho confirmar a transferência. As filas de trabalho/DLQ existentes continuam classic; nenhuma fila existente é apagada ou muda de tipo.

O intervalo da etapa `n` é `min(RETRY_MAX_INTERVAL_MS, round(RETRY_INITIAL_INTERVAL_MS * RETRY_MULTIPLIER^(n-1)))`. Os padrões são **1 s, 2 s e 4 s**, com multiplicador 2 e teto de 30 s. TTL fica em políticas, permitindo atualizar o backoff sem recriar as filas. O gerador de definições valida os parâmetros e aplica o vhost configurado também às políticas.

| Variável | Padrão | Uso |
|---|---|---|
| `RETRY_INITIAL_INTERVAL_MS` | `1000` | Intervalo inicial positivo, configurado no init do broker |
| `RETRY_MULTIPLIER` | `2` | Multiplicador finito maior que 1 |
| `RETRY_MAX_INTERVAL_MS` | `30000` | Teto maior ou igual ao intervalo inicial |
| `RETRY_CONFIRM_TIMEOUT_MS` | `5000` | Espera máxima positiva pela confirmação de publicação |
| `NOTIFICACOES_RETRY_EXCHANGE` | `notificacoes.retry` | Exchange de espera previamente provisionada |
| `NOTIFICACOES_DLX` | `notificacoes.dlx` | Exchange da DLQ previamente provisionada |

Em infraestrutura externa, esses nomes e políticas devem ser provisionados de forma coerente. Alterar somente os nomes no ambiente da aplicação não cria recursos. Os timeouts SMTP continuam configuráveis por `MAIL_*_TIMEOUT_MS`; lock timeout permanece em `PROCESSAMENTO_LOCK_TIMEOUT`.

### Segurança do encaminhamento e contagem

As cópias preservam o corpo, `eventoId`, destinatários e propriedades úteis. São persistentes, removem a expiração específica da mensagem original e carregam `x-notificacoes-tentativa` (2, 3 ou 4 nas novas tentativas) e `x-notificacoes-categoria`. A entrega inicial sem header corresponde à tentativa 1; valores fora de 1..4 ou com tipo inválido seguem diretamente para DLQ, sem executar a ação.

O publicador usa conexão de publicação, `mandatory=true`, returns e publisher confirms correlacionados. A origem recebe ACK individual somente após confirm positivo e ausência de return. Se isso falhar, a origem é preservada para recuperação do transporte. Não se chama `basicReject` para transferir falhas de aplicação pela DLX classic: a publicação explícita evita confirmar a origem antes da entrega segura à DLQ. As DLQs mantêm TTL de sete dias.

O limite de quatro é por **cadeia lógica de tentativas**, armazenado no broker e preservado em reentregas após retries/reinícios. Falhas de transporte podem reentregar a mesma tentativa; uma queda entre confirmação da cópia e ACK da origem pode duplicar a cópia. Essas entregas adicionais não representam nova etapa de backoff, e o marcador da MS-NOT-05 protege eventos concluídos. Não há garantia de exatamente uma vez para SMTP; a janela de duplicação documentada continua válida.

Logs de DLQ informam somente canal, tentativa e categoria; não persistem payload em tabela nem marcam um evento com falha como sucesso. O circuit breaker e a pausa automática complementam essa política.

### Aplicação em ambiente existente e testes

As definições são importadas no boot do RabbitMQ. Depois de mudar a topologia/backoff, é necessário atualizar o init e reiniciar/recriar o broker com o mesmo volume. Para aplicar esta task à stack local em uma janela de reinício:

```powershell
docker compose up -d --build --force-recreate --wait --wait-timeout 240
```

O comando recria containers, preservando os volumes; **não use `down -v`**. A identidade do nó e as filas antigas são mantidas. Apenas reconstruir a aplicação com o broker antigo ainda em execução não importa as novas definições. Para repetir a suíte, use os comandos `mensageria-it` com o projeto `ms-not-08-test`. O perfil `postgres-it` continua excluindo os testes que dependem de broker/SMTP.

Reprocessamento manual de uma mensagem da DLQ exige corrigir a causa e reiniciar a cadeia removendo `x-notificacoes-tentativa`/`x-notificacoes-categoria` antes de publicar o mesmo payload/ID no canal original; preservar o marcador de sucesso continua obrigatório. O teste de infraestrutura sem consumidores continua verificando a DLX nativa, enquanto os testes da aplicação verificam o encaminhamento confirmado.

Detalhes: [implementação](docs/tasks/ms-not-08-retries-dlq.md) e [evidências](docs/testes/ms-not-08-retries-dlq.md). Referências: [confirms e returns no Spring AMQP](https://github.com/spring-projects/spring-amqp-samples/tree/main/spring-rabbit-confirms-returns) e [dead lettering seguro em quorum queues](https://www.rabbitmq.com/docs/4.1/quorum-queues).

## JWT do Keycloak — MS-NOT-10

A API é um OAuth2 Resource Server stateless. Envie o access token em `Authorization: Bearer <token>`. São validados assinatura RS256 pelas chaves públicas JWKS, emissor exato, audiência, expiração obrigatória, `nbf` quando presente e `sub` não vazio. A validação temporal padrão do Spring Security tolera 60 segundos de diferença entre relógios. Token ausente/inválido recebe **401** com challenge Bearer; token válido sem papel permitido recebe **403**. Cookies, sessão, HTTP Basic e parâmetro `access_token` não autenticam a API.

Somente `TECNICO` e `GESTOR`, lidos de `realm_access.roles`, autorizam `/notificacoes` e `/notificacoes/**`. `CLIENTE`, client roles em `resource_access`, scopes e claims alternativos não concedem acesso. A identidade vem de `IdentidadeAutenticada.sub()`, que entrega o `sub` opaco do JWT validado, sem normalização, consulta ao backend/Keycloak ou destinatário recebido por parâmetro/header. Todas as operações da API filtram por esse sub.

| Variável | Padrão | Uso |
|---|---|---|
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8081/realms/flowsla` | Deve coincidir exatamente com o `iss` do token emitido ao frontend |
| `KEYCLOAK_JWK_SET_URI` | Direto: issuer local `/protocol/openid-connect/certs`; Compose: `http://host.docker.internal:8081/realms/flowsla/protocol/openid-connect/certs` | Endpoint de chaves acessível pela aplicação |
| `KEYCLOAK_AUDIENCE` | `ms-notificacoes` | Deve constar em `aud` do access token |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Lista de origens explícitas separadas por vírgula; vazio desabilita acesso entre origens |

O `.env.example` usa o endereço JWKS do Docker Desktop. Para executar Java diretamente no host, configure `KEYCLOAK_JWK_SET_URI` com `localhost` ou a URL externa apropriada. Em Linux, configure um endereço roteável a partir do container ou uma rede compartilhada provisionada pela operação. Defina as URLs HTTPS do seu Keycloak no ambiente de produção.

O issuer público e o endereço interno de chaves podem ser diferentes: não substitua o issuer público pelo hostname interno do container. Não há discovery nem contato com Keycloak ao iniciar; a leitura das chaves acontece ao validar tokens e usa o cache/renovação JWKS do Spring Security. Sem chaves disponíveis, tokens que precisem delas são recusados; consumidores e inicialização permanecem independentes do IdP.

O realm `flowsla` do backend consultado já possui o mapper `audience-ms-notificacoes` nos clients `flowsla-frontend` e `flowsla-swagger`, além dos papéis do realm esperados. Isso permite validar o mesmo access token sem alterar o backend. Realms já existentes precisam ter esses mappers efetivamente aplicados: editar um arquivo de importação não atualiza automaticamente um realm persistido.

CORS aceita somente as origens listadas, métodos `GET`/`PATCH` e headers `Authorization`/`Content-Type` nas rotas de notificações. Preflight válido dispensa token; origem, método ou header não permitido recebe 403. Não há credenciais por cookie nem curingas. O frontend deve enviar o Bearer em cada requisição.

`GET /notificacoes` e `PATCH /notificacoes/{id}/lida` estão implementados na MS-NOT-11. Os testes isolados da MS-NOT-10 preservam seus controllers exclusivos para verificar a segurança sem depender do banco. `/actuator/health` e `/actuator/prometheus` estão acessíveis sem token; `/actuator/info` e rotas fora desse escopo permanecem negados.

### Teste com Keycloak isolado

O teste usa Keycloak 26.4, sem portas públicas e sem acessar o backend. O realm de teste tem três usuários fictícios e uma senha temporária gerada no processo. O grant de senha existe somente nesse client de automação; os clients de frontend do ambiente real continuam usando Authorization Code com PKCE.

```powershell
$env:IT_KEYCLOAK_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
try {
    docker compose -p ms-not-10-test -f compose.yaml -f infra/compose.keycloak-it.yaml run --build --rm keycloak-it
} finally {
    docker compose -p ms-not-10-test -f compose.yaml -f infra/compose.keycloak-it.yaml down
    Remove-Item Env:IT_KEYCLOAK_PASSWORD
}
```

O perfil `keycloak-it` roda somente a integração JWT/Keycloak, além dos testes locais. `postgres-it` e `mensageria-it` continuam independentes do IdP. Após ajustar as variáveis de segurança, atualize a aplicação local com `docker compose up -d --build --no-deps ms-notificacoes`.

Detalhes: [implementação](docs/tasks/ms-not-10-jwt-keycloak.md) e [testes](docs/testes/ms-not-10-jwt-keycloak.md). Referências: [validação JWT no Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) e [importação/variáveis no Keycloak](https://www.keycloak.org/server/importExport).

## Consulta e leitura — MS-NOT-11

O contrato está em [docs/openapi.json](docs/openapi.json), OpenAPI 3.1. O serviço não adiciona Swagger UI ou uma rota pública de documentação. Os dois endpoints exigem `Authorization: Bearer <access-token>` de TECNICO/GESTOR e usam exclusivamente o `sub` autenticado. Não recebem destinatário como parâmetro de negócio.

| Operação | Resposta |
|---|---|
| `GET /notificacoes?page=0&size=20` | 200 com itens, metadados da página e total de não lidas do usuário |
| `PATCH /notificacoes/{id}/lida` | 204 sem corpo, inclusive se já lida |
| UUID inexistente ou pertencente a outro destinatário | Mesmo 404 com `application/problem+json` |
| Parâmetro fora do limite, não inteiro ou id não conversível em UUID | 400 com `application/problem+json` |
| Token ausente/inválido ou papel não permitido | 401/403, conforme a MS-NOT-10 |

`page` começa em zero e `size` aceita 1..100, com padrão 20. `page * size` não pode exceder 2147483647, limite de deslocamento suportado pelo JPA. A ordenação é fixa por `criadoEm DESC, id DESC`; `sort` e parâmetros extras de identidade não alteram a ordem nem o destinatário. Página além do fim retorna itens vazios, mantendo os totais.

Exemplo de resposta:

```json
{
  "itens": [{
    "id": "00000000-0000-0000-0000-000000000001",
    "tipo": "ATRIBUICAO",
    "chamadoId": "chamado-opaco",
    "numeroChamado": "#1042",
    "titulo": "Manutenção",
    "mensagem": "Você foi atribuído ao chamado #1042 — Manutenção.",
    "criadoEm": "2026-11-12T13:00:00Z",
    "lidaEm": null
  }],
  "pagina": 0,
  "tamanho": 20,
  "totalElementos": 1,
  "totalPaginas": 1,
  "totalNaoLidas": 1
}
```

`totalNaoLidas` inclui todas as notificações não lidas do usuário, além da página atual. A consulta e suas contagens usam uma transação de leitura REPEATABLE READ, obtendo um snapshot coerente por resposta. O estado pode mudar entre requisições de polling/paginação.

O DTO não expõe `destinatarioSub` ou `eventoId`. `mensagem` é texto simples derivado dos dados persistidos e deve ser renderizado como texto. `lidaEm=null` representa uma notificação não lida.

A marcação usa um único UPDATE com ID e sub na cláusula WHERE. `coalesce(lidaEm, ...)` mantém o primeiro instante registrado, inclusive com requisições concorrentes. O relógio vem do `Clock` do serviço; se estiver anterior à criação da notificação, a data é limitada à criação para respeitar a restrição do banco. Repetir o PATCH pode executar o UPDATE novamente, mas não altera a primeira data nem o resultado HTTP.

O `chamadoId` permite navegar ao chamado e não concede autorização sobre ele. Ao abrir o chamado, o frontend deve consultar o app-principal; se não houver mais acesso, apresenta um aviso sem dados do chamado. A API de notificações não consulta o backend de forma síncrona para listar ou marcar notificações.

Para verificar com um token obtido no login do Keycloak:

```powershell
$porta = (docker compose port ms-notificacoes 8081).Split(':')[-1]
$headers = @{ Authorization = "Bearer $token" }
$pagina = Invoke-RestMethod "http://localhost:$porta/notificacoes?page=0&size=20" -Headers $headers
if ($pagina.itens.Count -gt 0) {
    $id = $pagina.itens[0].id
    Invoke-WebRequest "http://localhost:$porta/notificacoes/$id/lida" -Method Patch -Headers $headers
}
```

O teste `NotificacoesApiPostgresIT` roda nos perfis `postgres-it` e `mensageria-it`, usando schema PostgreSQL exclusivo, controllers/filtros reais e decoder substituído apenas para isolar os casos de dados. Assinatura e emissão Keycloak continuam cobertas pelos testes da MS-NOT-10; a validação operacional desta task também exercita a imagem final com JWT real e eventos no RabbitMQ. Não há migration nova nem variável de ambiente nova.

Detalhes: [implementação](docs/tasks/ms-not-11-api-notificacoes.md) e [evidências](docs/testes/ms-not-11-api-notificacoes.md).

## Retenção e minimização — MS-NOT-12

A rotina automática remove notificações internas cuja criação seja **anterior** ao instante atual menos 90 dias. O registro exatamente na fronteira é preservado. A rotina fixa o limite no começo da execução e apaga em lotes, cada qual em sua própria transação; marcadores de `evento_processado` permanecem para proteger a idempotência.

| Variável | Padrão | Uso |
|---|---|---|
| `RETENCAO_CRON` | `0 0 3 * * *` | Expressão cron Spring de seis campos; diariamente às 03:00 no fuso configurado. `-` desabilita o agendamento |
| `RETENCAO_ZONE` | `UTC` | Fuso da programação, por exemplo `America/Sao_Paulo` |
| `RETENCAO_TAMANHO_LOTE` | `500` | Quantidade por transação, de 1 a 10000 |

O prazo de 90 dias é fixo, conforme RN-59. O índice de criação existente ajuda a localizar as linhas. `FOR UPDATE SKIP LOCKED` permite execuções simultâneas de réplicas sem apagar a mesma linha; registros bloqueados por outras transações podem ficar para a próxima execução. Um erro interrompe a execução atual; lotes já confirmados permanecem removidos e os demais serão considerados no próximo agendamento.

E-mail do destinatário e texto do posicionamento são usados no envio, sem coluna correspondente no banco. As filas de trabalho/retry e DLQ carregam temporariamente o evento necessário ao processamento; as duas DLQs têm TTL de sete dias. Marcadores de sucesso são preservados independentemente das notificações e não contêm payload. Sua retenção indefinida deve ser considerada na operação.

O tratador de erros AMQP mantém a classificação fatal e o comportamento de rejeição/descarte, mas registra apenas uma mensagem genérica, sem imprimir payload ou texto de exceção. O logger dos containers Rabbit fica em ERROR porque alguns WARN de encerramento incluem mensagens completas; isso também suprime outros avisos desse logger. Mantenha logs de protocolo SMTP/AMQP e SQL de parâmetros desativados em ambientes com dados reais. A aplicação registra no expurgo somente o total de linhas removidas.

Detalhes: [implementação](docs/tasks/ms-not-12-retencao-minimizacao.md) e [testes](docs/testes/ms-not-12-retencao-minimizacao.md).

## Recuperação SMTP — MS-NOT-09

O circuit breaker Resilience4j envolve o envio SMTP. Com os padrões, a primeira falha de disponibilidade abre o circuito, pausa o listener de email e mantém as entregas na fila sem avançar tentativas. A sondagem independente verifica conexão/TLS/autenticação e retoma o listener ao fechar o circuito. O canal interno não é pausado.

Configure `SMTP_CIRCUIT_BREAKER_ENABLED` (padrão `true`), `SMTP_CIRCUIT_WINDOW_SIZE` (`1`), `SMTP_CIRCUIT_MIN_CALLS` (`1`), `SMTP_CIRCUIT_FAILURE_RATE` (`100`), `SMTP_CIRCUIT_OPEN_WAIT` (`30s`) e `SMTP_PROBE_INTERVAL` (`5s`). A sondagem SMTP e o expurgo de retenção usam agendadores separados. A implementação da MS-NOT-09, anteriormente em outra branch, foi integrada à árvore desta task. Detalhes: [decisões](docs/tasks/ms-not-09-recuperacao-smtp.md) e [testes originais](docs/testes/ms-not-09-recuperacao-smtp.md).

## Observabilidade — MS-NOT-13

`/actuator/prometheus` expõe métricas Micrometer sem token, no mesmo servidor HTTP da aplicação. Restrinja esse endpoint por rede ou proxy ao coletor de métricas em ambientes implantados. No Compose a porta da aplicação é publicada somente no loopback do host; os demais serviços da rede Compose conseguem alcançá-la. `/actuator/health` agrega as dependências; `/actuator/health/liveness` indica a vida do processo sem depender delas e é usado pelo Docker HEALTHCHECK. O probe `/actuator/health/readiness` representa o estado de disponibilidade do Spring e, por padrão, não verifica dependências externas.

| Métrica Prometheus | Significado |
|---|---|
| `notificacoes_eventos_processados_total{canal}` | Eventos efetivamente processados após commit; duplicatas não incrementam. É incrementada antes do ACK, inclusive se o ACK falhar |
| `notificacoes_entregas_total{canal,resultado}` | Entregas cujo ACK retornou, com resultado `processado` ou `duplicado` |
| `notificacoes_falhas_total{canal,categoria}` | Ocorrências por categoria: temporária, definitiva, transporte, encaminhamento ou indisponibilidade SMTP. Uma mesma entrega pode gerar mais de uma ocorrência |
| `notificacoes_smtp_circuito_estado{estado}` | 1 para o estado atual e 0 para os demais; uma série por estado do Resilience4j |
| `notificacoes_smtp_circuito_habilitado` | 1 quando habilitado, 0 quando desabilitado; desabilitado não é representado como CLOSED |
| `notificacoes_smtp_circuito_transicoes_total{de,para}` | Contagem de transições observadas, inclusive HALF_OPEN de curta duração |

Contadores são locais à instância, reiniciam com o processo e são agregados no Prometheus. As etiquetas personalizadas têm apenas canal, resultado, categoria e estados; não contêm destinatário, chamado ou eventoId. Para correlacionar eventos válidos, os logs registram o SHA-256 do `eventoId` em UTF-8, sem payload ou texto SMTP. O hash é um identificador pseudônimo, não anonimização de identificadores previsíveis. Payloads que não passam pela validação não têm ID confiável para correlacionar.

### Prometheus e Grafana locais

Defina `GRAFANA_ADMIN_PASSWORD` no ambiente e execute:

```powershell
docker compose -f compose.yaml -f infra/compose.observabilidade.yaml up -d --build --wait --wait-timeout 240
```

Grafana fica em `http://localhost:13000` e Prometheus em `http://localhost:19090`; as portas e `GRAFANA_ADMIN_USER` são configuráveis. A senha é obrigatória e não é versionada. O volume do Grafana preserva a conta: mudar a variável depois da primeira inicialização não redefine sua senha.

O complemento habilita `rabbitmq_management` e `rabbitmq_prometheus` via arquivo de plugins e coleta `/metrics/per-object` em `rabbitmq:15692`, sem publicar essa porta no host. A troca do arquivo de plugins requer recriar/reiniciar o container RabbitMQ; o comando acima pode fazê-lo, mantendo seus volumes. Planeje essa interrupção ao aplicá-lo em uma stack existente. O complemento define a lista de plugins habilitados; instalações com outros plugins precisam conciliá-la. A coleta por objeto é adequada à topologia local e pode exigir filtros/ajustes em brokers com muitas filas.

O Prometheus descobre instâncias da aplicação por DNS e coleta também as métricas por fila do RabbitMQ. O dashboard provisionado `FlowSLA / Notificações e filas` mostra profundidade, consumidores, taxa de entregas, DLQs, eventos/falhas e circuito. A primeira amostra de uma série aparece após sua criação e coleta; `rate` precisa de amostras suficientes. `/actuator/prometheus` e o endpoint do plugin Rabbit devem permanecer em rede confiável.

Para validar em `ms-not-13-test`, mantenha os serviços da stack isolada em execução e rode `python infra/smoke_observabilidade.py`. O roteiro publica eventos fictícios e interrompe/reinicia **somente** o Mailpit dessa stack; verifica coleta, dashboard, isolamento do canal interno, liveness, circuito e recuperação. Não executa purge nem remove volumes. Detalhes: [implementação](docs/tasks/ms-not-13-observabilidade.md) e [testes](docs/testes/ms-not-13-observabilidade.md).


## Testes automatizados — MS-NOT-14

Com Java 21, Docker em execução e acesso aos repositórios de dependências/imagens, execute na raiz:

```powershell
.\mvnw.cmd -B -Pcompleto-it verify
```

Linux/macOS: `./mvnw -B -Pcompleto-it verify`. O perfil executa os testes locais e toda a integração, incluindo segurança com tokens Keycloak reais e o fluxo RabbitMQ → PostgreSQL → API. PostgreSQL, RabbitMQ, Mailpit e Keycloak são criados pelo Testcontainers com portas aleatórias, credenciais temporárias e dados descartáveis. Não precisa de `.env` nem da stack Compose iniciada. Docker indisponível ou teste falhando retorna erro; os testes não são ignorados por falta de infraestrutura.

Os containers são encerrados ao terminar; Ryuk acompanha sua limpeza. Não execute com reutilização de containers. Os dados sintéticos da suíte são descartados. Os relatórios XML e texto ficam em `target/surefire-reports` (testes locais) e `target/failsafe-reports` (integração). O Maven Wrapper baixa Maven/dependências no cache do usuário na primeira execução. Testcontainers precisa de acesso ao daemon Docker; em runners dentro de containers, configure o socket e a conectividade com as portas publicadas conforme o ambiente.

`test` continua executando somente os testes locais, sem criar containers. Os perfis `postgres-it`, `mensageria-it` e `keycloak-it` preservam os runners externos existentes. Escolha apenas um perfil por execução. A automação de CI pertence à MS-NOT-17.

Matriz de cobertura: [implementação](docs/tasks/ms-not-14-testes-automatizados.md). Evidências: [testes](docs/testes/ms-not-14-testes-automatizados.md).
