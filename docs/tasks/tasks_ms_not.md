# NIVELO — Todas as tasks do microsserviço de notificações (MS-NOT-01 a MS-NOT-18)

> **Fonte:** issues #85 a #102 do repositório [gestao-SLA-prestadoras-de-servico/backend](https://github.com/gestao-SLA-prestadoras-de-servico/backend).
> **Consulta:** 10 de novembro de 2026. Os textos e checklists abaixo foram transcritos das issues do GitHub, sem alterar seu conteúdo.

**Total:** 18 tasks.

## Índice das tasks

| Task | Título | Issue no GitHub |
|---|---|---|
| [MS-NOT-01](#ms-not-01) | Criar a estrutura do microsserviço | [#85](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/85) |
| [MS-NOT-02](#ms-not-02) | Documentar e validar os contratos dos eventos | [#86](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/86) |
| [MS-NOT-03](#ms-not-03) | Configurar Docker e a topologia do RabbitMQ | [#87](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/87) |
| [MS-NOT-04](#ms-not-04) | Criar o banco e as migrations Flyway | [#88](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/88) |
| [MS-NOT-05](#ms-not-05) | Implementar idempotência e controle de concorrência | [#89](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/89) |
| [MS-NOT-06](#ms-not-06) | Consumir e persistir notificações internas | [#90](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/90) |
| [MS-NOT-07](#ms-not-07) | Implementar os e-mails ao cliente | [#91](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/91) |
| [MS-NOT-08](#ms-not-08) | Implementar retries e encaminhamento para DLQ | [#92](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/92) |
| [MS-NOT-09](#ms-not-09) | Implementar circuit breaker e recuperação do SMTP | [#93](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/93) |
| [MS-NOT-10](#ms-not-10) | Proteger a API com JWT do Keycloak | [#94](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/94) |
| [MS-NOT-11](#ms-not-11) | Criar a API de consulta e marcação de leitura | [#95](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/95) |
| [MS-NOT-12](#ms-not-12) | Implementar retenção e minimização de dados | [#96](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/96) |
| [MS-NOT-13](#ms-not-13) | Implementar observabilidade do serviço e das filas | [#97](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/97) |
| [MS-NOT-14](#ms-not-14) | Automatizar testes funcionais, de integração e segurança | [#98](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/98) |
| [MS-NOT-15](#ms-not-15) | Validar recuperação de falhas e isolamento dos canais | [#99](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/99) |
| [MS-NOT-16](#ms-not-16) | Comprovar escalabilidade independente | [#100](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/100) |
| [MS-NOT-17](#ms-not-17) | Configurar integração contínua | [#101](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/101) |
| [MS-NOT-18](#ms-not-18) | Documentar execução, operação e decisões técnicas | [#102](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/102) |

---

<a id="ms-not-01"></a>

## [MS-NOT-01] - Criar a estrutura do microsserviço

**Issue original:** [#85](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/85)

**Contexto e objetivo:** Preparar um serviço independente para concentrar o envio de e-mails e o gerenciamento das notificações internas.

**Requisitos:** Arquitetura da seção 3.1; RN-58 — isolamento em relação ao app-principal.

**Depende de:** Nenhuma outra task.

### Critérios de aceite (Definition of Done)
- [ ] Projeto utiliza Java 21, Spring Boot 4 e Maven.
- [ ] Serviço compila e inicia com configurações fornecidas por variáveis de ambiente.
- [ ] Estrutura contempla API, processamento de eventos, persistência e integrações.
- [ ] Credenciais ficam fora do código versionado.

### Passos técnicos / Checklist
- [ ] Inicializar o projeto `ms-notificacoes` e adicionar o Maven Wrapper.
- [ ] Adicionar dependências para HTTP, validação, persistência, mensageria, segurança, e-mail e observabilidade.
- [ ] Organizar os pacotes e criar exemplos das variáveis de configuração.

---

<a id="ms-not-02"></a>

## [MS-NOT-02] - Documentar e validar os contratos dos eventos

**Issue original:** [#86](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/86)

**Contexto e objetivo:** Estabelecer como o app-principal comunica os eventos ao microsserviço, fornecendo os dados necessários para produzir cada notificação.

**Requisitos:** RN-46, RN-52 a RN-54 e RN-58 — mensageria, conteúdo dos eventos e autonomia do consumidor.

**Depende de:** Alinhamento com o contrato de publicação do app-principal.

### Critérios de aceite (Definition of Done)
- [ ] Contrato contempla eventoId, tipo, chamado, número legível, título e destinatários.
- [ ] textoPosicionamento é previsto no evento de primeiro posicionamento.
- [ ] Todos os tipos de evento têm exemplos e canal de destino documentados.
- [ ] Mensagens com campos obrigatórios ausentes ou tipos desconhecidos são identificadas como inválidas.

### Passos técnicos / Checklist
- [ ] Criar os DTOs e as validações das mensagens.
- [ ] Documentar a matriz de eventos, canais e destinatários.
- [ ] Preparar exemplos de payloads válidos e inválidos.
- [ ] Registrar que o eventoId, inclusive o determinístico de violação de SLA, é gerado pelo app-principal.

---

<a id="ms-not-03"></a>

## [MS-NOT-03] - Configurar Docker e a topologia do RabbitMQ

**Issue original:** [#87](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/87)

**Contexto e objetivo:** Disponibilizar um ambiente reproduzível e manter o processamento de e-mails independente das notificações internas.

**Requisitos:** RF-51; RN-48, RN-56 e RN-57 — filas por canal, DLQ e expiração. Docker decorre da arquitetura.

**Depende de:** MS-NOT-01 e MS-NOT-02.

### Critérios de aceite (Definition of Done)
- [ ] Ambiente inicia o microsserviço, PostgreSQL, RabbitMQ e Mailpit.
- [ ] Exchange notificacoes encaminha mensagens às filas notificacoes.email e notificacoes.interna.
- [ ] Filas e mensagens de trabalho são configuradas para sobreviver a reinícios do broker.
- [ ] Cada canal possui DLQ com expiração das mensagens em 7 dias.

### Passos técnicos / Checklist
- [ ] Criar Dockerfile e configuração Docker Compose.
- [ ] Configurar exchange, filas, bindings e políticas das DLQs.
- [ ] Configurar volumes, rede, credenciais e verificações de disponibilidade.
- [ ] Permitir executar múltiplas instâncias do microsserviço.

---

<a id="ms-not-04"></a>

## [MS-NOT-04] - Criar o banco e as migrations Flyway

**Issue original:** [#88](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/88)

**Contexto e objetivo:** Persistir as notificações internas e os registros necessários ao controle de processamento.

**Requisitos:** RN-55, RN-58 e RN-59; PostgreSQL e Flyway definidos na arquitetura.

**Depende de:** MS-NOT-01 e MS-NOT-03.

### Critérios de aceite (Definition of Done)
- [ ] Serviço utiliza banco lógico e credenciais próprios.
- [ ] Notificação armazena destinatário sub, evento, tipo, chamado, número, título e datas de criação e leitura.
- [ ] Existe estrutura para registrar eventos processados com sucesso.
- [ ] Migrations criam as tabelas, restrições e índices em um banco vazio.

### Passos técnicos / Checklist
- [ ] Modelar entidades e repositórios.
- [ ] Criar migrations Flyway.
- [ ] Criar índices para consulta por destinatário, ordenação e notificações não lidas.
- [ ] Criar restrições que sustentem o controle de duplicações.

---

<a id="ms-not-05"></a>

## [MS-NOT-05] - Implementar idempotência e controle de concorrência

**Issue original:** [#89](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/89)

**Contexto e objetivo:** Tratar reentregas do RabbitMQ e processamento simultâneo por várias instâncias.

**Requisitos:** RN-55 e RN-115 — registro após sucesso e consumidores idempotentes; RN-54 como dependência da identidade do evento.

**Depende de:** MS-NOT-02 e MS-NOT-04.

### Critérios de aceite (Definition of Done)
- [ ] Eventos com sucesso registrado são reconhecidos e descartados nas reentregas.
- [ ] Notificações internas e registro de processamento são confirmados na mesma transação.
- [ ] Processamento concorrente do mesmo evento não cria notificações internas duplicadas.
- [ ] Limitação de duplicação do SMTP entre envio e gravação do sucesso está documentada.

### Passos técnicos / Checklist
- [ ] Implementar identificação única por evento e canal, contemplando os destinatários internos.
- [ ] Coordenar o processamento concorrente usando o banco.
- [ ] Confirmar a mensagem ao RabbitMQ após a conclusão do processamento.
- [ ] Manter os marcadores de processamento separados da exclusão das notificações.

---

<a id="ms-not-06"></a>

## [MS-NOT-06] - Consumir e persistir notificações internas

**Issue original:** [#90](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/90)

**Contexto e objetivo:** Transformar os eventos operacionais em notificações disponíveis no sino de técnicos e gestores.

**Requisitos:** RF-53 a RF-55, RF-57 e RF-58; RN-35 a RN-41, RN-53 e RN-58. RN-135 orienta os eventos de transferência produzidos pelo app-principal.

**Depende de:** MS-NOT-03 e MS-NOT-05; eventos com destinatários definidos pelo app-principal.

### Critérios de aceite (Definition of Done)
- [ ] Consumidor atende atribuição, reatribuição, remoção, reabertura, conclusão e violação de SLA.
- [ ] Uma notificação é criada para cada destinatário recebido.
- [ ] Novas notificações são criadas como não lidas e vinculadas ao chamado.
- [ ] Persistência utiliza somente os dados recebidos no evento.

### Passos técnicos / Checklist
- [ ] Implementar o consumidor da fila notificacoes.interna.
- [ ] Criar a composição das mensagens por tipo de evento.
- [ ] Persistir o conjunto de notificações e o sucesso do evento de forma transacional.
- [ ] Aplicar o controle de duplicações do MS-NOT-05.

---

<a id="ms-not-07"></a>

## [MS-NOT-07] - Implementar os e-mails ao cliente

**Issue original:** [#91](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/91)

**Contexto e objetivo:** Comunicar ao cliente o primeiro posicionamento, a resolução e a proximidade do encerramento automático.

**Requisitos:** RF-52; RN-08, RN-33, RN-34, RN-41 e RN-53.

**Depende de:** MS-NOT-03 e MS-NOT-05; evento de aviso gerado pelo app-principal um dia útil antes do encerramento.

### Critérios de aceite (Definition of Done)
- [ ] Os três tipos de e-mail possuem assunto e conteúdo correspondentes ao evento.
- [ ] Destinatário é o e-mail de contato recebido na mensagem.
- [ ] Primeiro posicionamento inclui o texto registrado pelo gestor.
- [ ] E-mails podem ser inspecionados no Mailpit.

### Passos técnicos / Checklist
- [ ] Implementar o consumidor da fila notificacoes.email.
- [ ] Configurar o cliente SMTP e os templates.
- [ ] Incluir número e título do chamado nos conteúdos.
- [ ] Escapar conteúdos fornecidos por usuários quando utilizados em HTML.

---

<a id="ms-not-08"></a>

## [MS-NOT-08] - Implementar retries e encaminhamento para DLQ

**Issue original:** [#92](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/92)

**Contexto e objetivo:** Recuperar falhas temporárias e separar mensagens cujo processamento falhou definitivamente.

**Requisitos:** RF-61; RN-47, RN-50, RN-51, RN-56 e RN-57; tratamento de falhas de banco descrito na seção 3.4.

**Depende de:** MS-NOT-06 e MS-NOT-07.

### Critérios de aceite (Definition of Done)
- [ ] Timeout, SMTP 4xx e falha temporária no banco recebem tentativa inicial e até três novas tentativas.
- [ ] Novas tentativas utilizam backoff exponencial configurável.
- [ ] SMTP 5xx e mensagens inválidas seguem diretamente para a DLQ do canal.
- [ ] Esgotamento das tentativas encaminha a mensagem à DLQ e registra a falha.

### Passos técnicos / Checklist
- [ ] Classificar erros temporários e definitivos.
- [ ] Configurar timeouts e controlar a quantidade de tentativas entre reentregas.
- [ ] Evitar multiplicação de retries entre diferentes camadas.
- [ ] Garantir encaminhamento seguro à DLQ antes de confirmar a mensagem original.

---

<a id="ms-not-09"></a>

## [MS-NOT-09] - Implementar circuit breaker e recuperação do SMTP

**Issue original:** [#93](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/93)

**Contexto e objetivo:** Evitar consumo contínuo durante uma indisponibilidade do SMTP e recuperar o processamento automaticamente.

**Requisitos:** RN-47 a RN-49 — proteção SMTP, isolamento dos canais e pausa do consumidor.

**Depende de:** MS-NOT-08.

### Critérios de aceite (Definition of Done)
- [ ] Circuit breaker abre diante de falhas de disponibilidade do SMTP.
- [ ] Consumidor de e-mail é pausado enquanto o circuito estiver aberto.
- [ ] Mensagens aguardando na fila preservam suas tentativas.
- [ ] Recuperação do SMTP retoma o consumo automaticamente e o canal interno permanece operacional.

### Passos técnicos / Checklist
- [ ] Configurar Resilience4j e seus parâmetros por ambiente.
- [ ] Integrar as mudanças do circuito ao controle do consumidor.
- [ ] Implementar sondagem SMTP agendada que funcione com o consumidor pausado.
- [ ] Implementar a transição de recuperação e a retomada do consumo.

---

<a id="ms-not-10"></a>

## [MS-NOT-10] - Proteger a API com JWT do Keycloak

**Issue original:** [#94](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/94)

**Contexto e objetivo:** Autenticar os usuários e restringir a API aos perfis autorizados.

**Requisitos:** RF-01 e RF-02; RN-43, RN-44, RN-129 e RN-130 — identidade pelo sub e autorização pelo papel do token.

**Depende de:** MS-NOT-01; configuração do Keycloak.

### Critérios de aceite (Definition of Done)
- [ ] Tokens têm assinatura, emissor e validade verificados.
- [ ] Apenas TECNICO e GESTOR acessam os endpoints de notificações.
- [ ] Usuário é identificado pelo sub do token.
- [ ] Requisições sem autenticação válida retornam 401; perfil não autorizado recebe 403.

### Passos técnicos / Checklist
- [ ] Configurar o serviço como OAuth2 Resource Server.
- [ ] Mapear os papéis emitidos pelo Keycloak.
- [ ] Centralizar a obtenção da identidade autenticada.
- [ ] Configurar acesso do frontend com as origens permitidas por ambiente.

---

<a id="ms-not-11"></a>

## [MS-NOT-11] - Criar a API de consulta e marcação de leitura

**Issue original:** [#95](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/95)

**Contexto e objetivo:** Permitir ao frontend apresentar as notificações do usuário, a contagem de não lidas e atualizar seu estado de leitura.

**Requisitos:** RF-59 e RF-60; RN-42 a RN-45. A verificação de acesso atual ao chamado depende do app-principal.

**Depende de:** MS-NOT-04 e MS-NOT-10.

### Critérios de aceite (Definition of Done)
- [ ] GET /notificacoes retorna página das próprias notificações, ordenadas das mais recentes, e contagem total de não lidas.
- [ ] PATCH /notificacoes/{id}/lida registra a leitura e retorna 204, inclusive quando já estiver lida.
- [ ] Identificador inexistente ou pertencente a outro usuário retorna 404 na marcação.
- [ ] Resposta inclui o identificador do chamado necessário à navegação e a API está documentada em OpenAPI.

### Passos técnicos / Checklist
- [ ] Criar DTOs, consultas paginadas e contagem de não lidas.
- [ ] Aplicar o filtro pelo sub em todas as operações.
- [ ] Implementar a marcação de leitura preservando a primeira data registrada.
- [ ] Documentar parâmetros, respostas e erros.

---

<a id="ms-not-12"></a>

## [MS-NOT-12] - Implementar retenção e minimização de dados

**Issue original:** [#96](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/96)

**Contexto e objetivo:** Aplicar a política de retenção das notificações e limitar o armazenamento de dados pessoais.

**Requisitos:** RF-62; RN-57 e RN-59; minimização definida na seção 6.4.

**Depende de:** MS-NOT-04, MS-NOT-06 e MS-NOT-07.

### Critérios de aceite (Definition of Done)
- [ ] Rotina diária remove notificações internas criadas há mais de 90 dias.
- [ ] Notificações dentro do prazo são preservadas.
- [ ] E-mail do destinatário e texto do posicionamento não são armazenados no banco ou nos logs.
- [ ] Exclusão de notificações preserva os marcadores usados para reconhecer eventos processados.

### Passos técnicos / Checklist
- [ ] Criar rotina agendada com horário configurável.
- [ ] Implementar exclusão em lotes.
- [ ] Revisar entidades, logs e tratamento de exceções quanto ao conteúdo registrado.
- [ ] Conferir a configuração de expiração das DLQs criada no MS-NOT-03.

---

<a id="ms-not-13"></a>

## [MS-NOT-13] - Implementar observabilidade do serviço e das filas

**Issue original:** [#97](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/97)

**Contexto e objetivo:** Permitir acompanhar a saúde do serviço, o processamento e os problemas de entrega.

**Requisitos:** RF-63 a RF-65; arquitetura da seção 3.1. Não há RN específica de observabilidade.

**Depende de:** MS-NOT-03, MS-NOT-06 e MS-NOT-09.

### Critérios de aceite (Definition of Done)
- [ ] Serviço expõe health checks e métricas para Prometheus.
- [ ] Há métricas de eventos processados, falhas por canal e estado do circuito.
- [ ] Painel Grafana mostra profundidade das filas, consumo e mensagens nas DLQs.
- [ ] Falha no SMTP é visível sem provocar reinício ou bloqueio do canal interno.

### Passos técnicos / Checklist
- [ ] Configurar Actuator, Micrometer e integração Prometheus.
- [ ] Instrumentar consumidores e circuit breaker.
- [ ] Configurar coleta de métricas do RabbitMQ e o dashboard.
- [ ] Adicionar logs correlacionados pelo eventoId, evitando dados pessoais nos rótulos das métricas.

---

<a id="ms-not-14"></a>

## [MS-NOT-14] - Automatizar testes funcionais, de integração e segurança

**Issue original:** [#98](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/98)

**Contexto e objetivo:** Verificar os comportamentos dos canais, da persistência e da API com dependências reais de infraestrutura.

**Requisitos:** Cobertura de RN-33 a RN-45, RN-53 a RN-55, RN-58, RN-59, RN-129, RN-130 e RN-135. Não há RN própria determinando a estrutura da suíte.

**Depende de:** MS-NOT-06, MS-NOT-07, MS-NOT-08, MS-NOT-11 e MS-NOT-12.

### Critérios de aceite (Definition of Done)
- [ ] Testes cobrem os tipos de evento, payloads inválidos e destinatários múltiplos nas notificações internas.
- [ ] Testes verificam reentrega e consumo concorrente sem duplicação interna.
- [ ] API é testada para paginação, contagem, leitura repetida, perfis e isolamento entre usuários.
- [ ] Expurgo é testado nos limites de 90 dias.
- [ ] Exemplos de contrato contemplam transferência para substituto e ausência de aviso ao técnico inativado, conforme eventos do produtor.

### Passos técnicos / Checklist
- [ ] Preparar PostgreSQL e RabbitMQ com Testcontainers.
- [ ] Criar fixtures dos eventos e usuários.
- [ ] Implementar testes unitários das regras e testes de integração dos fluxos.
- [ ] Preparar captura SMTP para verificar destinatários e conteúdo dos e-mails.

---

<a id="ms-not-15"></a>

## [MS-NOT-15] - Validar recuperação de falhas e isolamento dos canais

**Issue original:** [#99](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/99)

**Contexto e objetivo:** Demonstrar que as mensagens são recuperadas após falhas e que uma indisponibilidade no e-mail preserva o funcionamento das notificações internas.

**Requisitos:** RN-46 a RN-51, RN-55 a RN-58 e RN-115; demonstração da seção 3.4. RN-113 e RN-114 pertencem ao cenário integrado com o app-principal.

**Depende de:** MS-NOT-09, MS-NOT-13 e MS-NOT-14.

### Critérios de aceite (Definition of Done)
- [ ] SMTP indisponível faz o circuito abrir e a fila de e-mail acumular.
- [ ] Notificações internas continuam sendo processadas durante a falha SMTP.
- [ ] Retorno do SMTP retoma o processamento e drena as mensagens pendentes.
- [ ] Falhas de banco, retries e encaminhamento à DLQ são demonstrados.
- [ ] Reinício do microsserviço e do broker preserva mensagens já aceitas pelo RabbitMQ.

### Passos técnicos / Checklist
- [ ] Preparar cenários reproduzíveis de interrupção e recuperação.
- [ ] Registrar filas, métricas e resultados antes, durante e depois das falhas.
- [ ] Verificar reentrega após interrupção entre persistência e confirmação.
- [ ] Executar o cenário de broker indisponível com a outbox quando o produtor estiver disponível.

---

<a id="ms-not-16"></a>

## [MS-NOT-16] - Comprovar escalabilidade independente

**Issue original:** [#100](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/100)

**Contexto e objetivo:** Demonstrar o efeito de aumentar as instâncias do serviço sobre a drenagem da fila de e-mail.

**Requisitos:** Cenário da seção 3.4 e exigência arquitetural da seção 8.3; RN-55 e RN-58 como restrições de idempotência e isolamento.

**Depende de:** MS-NOT-13 e MS-NOT-15.

### Critérios de aceite (Definition of Done)
- [ ] Mesma carga é executada com uma e quatro instâncias.
- [ ] Toxiproxy aplica latência SMTP controlada e documentada.
- [ ] Relatório apresenta tempo de drenagem, vazão, erros e verificação de duplicações.
- [ ] Resultado demonstra o comportamento de escala, identificando os gargalos encontrados, sem percentual de ganho presumido.

### Passos técnicos / Checklist
- [ ] Criar um gerador reproduzível de eventos.
- [ ] Configurar Toxiproxy entre o serviço e o Mailpit.
- [ ] Manter carga, recursos e concorrência por instância comparáveis.
- [ ] Executar os cenários e registrar gráficos e conclusões.

---

<a id="ms-not-17"></a>

## [MS-NOT-17] - Configurar integração contínua

**Issue original:** [#101](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/101)

**Contexto e objetivo:** Automatizar a verificação do projeto e a construção da imagem a cada alteração.

**Requisitos:** Atividade técnica recomendada; não possui RN ou RF específica na proposta.

**Depende de:** MS-NOT-03 e MS-NOT-14.

### Critérios de aceite (Definition of Done)
- [ ] Pipeline executa compilação e testes.
- [ ] Testes de integração executam com suas dependências de infraestrutura.
- [ ] Falhas de compilação ou testes fazem o pipeline falhar.
- [ ] Pipeline constrói a imagem Docker e disponibiliza os relatórios de execução.

### Passos técnicos / Checklist
- [ ] Criar workflow no GitHub Actions.
- [ ] Configurar Java, cache Maven e suporte à execução dos containers de teste.
- [ ] Adicionar etapas de testes, relatórios e build da imagem.
- [ ] Configurar variáveis e segredos necessários.

---

<a id="ms-not-18"></a>

## [MS-NOT-18] - Documentar execução, operação e decisões técnicas

**Issue original:** [#102](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/102)

**Contexto e objetivo:** Permitir que outra pessoa execute, integre e opere o microsserviço usando a documentação do projeto.

**Requisitos:** Documentação das decisões relacionadas às RN-47 a RN-59 e RN-115; referências às seções 3.4, 3.6 e 6.4. Não há RN específica exigindo README ou manual operacional.

**Depende de:** MS-NOT-02, MS-NOT-11, MS-NOT-15, MS-NOT-16 e MS-NOT-17.

### Critérios de aceite (Definition of Done)
- [ ] README contém pré-requisitos, variáveis e comandos para execução.
- [ ] Contratos de eventos e API estão acessíveis e atualizados.
- [ ] Manual descreve diagnóstico, DLQ e reprocessamento pelo RabbitMQ.
- [ ] Decisões de idempotência, recuperação SMTP e retenção estão registradas.
- [ ] Resultados dos testes de resiliência e escala estão vinculados à documentação.

### Passos técnicos / Checklist
- [ ] Escrever o guia de configuração e execução.
- [ ] Documentar a integração com app-principal, frontend e Keycloak.
- [ ] Criar procedimentos para inspeção das filas e recuperação de mensagens.
- [ ] Registrar as decisões técnicas e anexar as evidências de validação.

---
