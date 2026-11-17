# Plataforma de Gestão de SLA para Prestadoras de Serviço

**Proposta, arquitetura, requisitos funcionais e regras de negócio**
Projeto Integrador 2026.2 — Desenvolvimento de Sistemas Corporativos

---

## 1. Proposta

### 1.1. O problema

Prestadoras de serviço técnico B2B — manutenção predial, refrigeração, assistência de equipamentos, infraestrutura de rede — assumem contratos com prazos acordados: responder ao cliente em X horas, comparecer ao local em Y horas, resolver em Z horas. Esses prazos são o produto que elas vendem.

Na prática, o controle desses prazos costuma ser feito em planilha, e-mail e grupo de mensagens. Isso gera três problemas concretos:

1. **A violação só é percebida quando o cliente reclama.** Sem apuração automática, ninguém sabe que um prazo estourou até alguém cobrar.
2. **A comprovação do atendimento é frágil.** Fotos ficam no celular do técnico ou em conversas de mensagem, sem vínculo com o chamado e sem garantia de que não foram alteradas.
3. **Não existe histórico defensável.** Quando o cliente questiona o cumprimento do contrato, a prestadora não tem como provar o que aconteceu, quando e por quem.

### 1.2. A solução

Um sistema corporativo que centraliza o ciclo de vida do chamado, calcula os prazos automaticamente a partir da política contratada, detecta violações sem intervenção humana e mantém as evidências de campo vinculadas ao atendimento, íntegras e auditáveis.

### 1.3. Público-alvo

Prestadoras de serviço técnico de pequeno e médio porte que operam sob contrato com múltiplos clientes corporativos, com equipe de campo e necessidade de comprovar cumprimento de prazo.

### 1.4. O que o sistema não é

- Não é um ERP: não faz faturamento, cobrança ou controle financeiro
- Não é um sistema de força de vendas ou CRM comercial
- Não gerencia estoque de peças
- Não faz roteirização ou otimização de deslocamento
- Não rastreia a localização do técnico

---

## 2. Visão geral

### 2.1. Perfis de usuário

| Perfil | Quem é | Papel no fluxo |
|---|---|---|
| **Cliente** | Contato da empresa contratante | Abre chamados, acompanha o andamento, valida a conclusão |
| **Técnico** | Profissional de campo da prestadora | Executa o atendimento presencial e registra as evidências |
| **Gestor** | Coordenador de operações da prestadora | Cadastra contratos e políticas, triagem, atribuição, relatórios e auditoria |

### 2.2. Fluxo principal

1. Gestor cadastra o cliente, o contrato e a política de SLA aplicável
2. Cliente abre um chamado sob um contrato vigente
3. Sistema calcula e grava os três prazos a partir da política e da prioridade
4. Gestor faz a triagem com o chamado ainda em `ABERTO`: define a prioridade definitiva e registra o primeiro posicionamento
5. Gestor atribui o chamado a um técnico, que é avisado pelo sino de notificações
6. Técnico comparece ao local e registra o atendimento
7. Técnico anexa ao menos uma evidência fotográfica
8. Sistema recebe a evidência, responde imediatamente e processa em segundo plano
9. Técnico conclui o atendimento; chamado passa a resolvido, e os gestores que ativaram a preferência de notificação no chamado são avisados pelo sino
10. Cliente valida — ou o sistema valida automaticamente após 3 dias úteis
11. Sistema apura o resultado dos três SLAs e congela a apuração

Se o cliente não concordar com a solução, ele reabre o chamado, que volta ao técnico atribuído — avisado pelo sino — e reinicia o ciclo operacional a partir do passo 6.

Em qualquer ponto do fluxo, se um prazo de SLA for violado, todos os gestores e o técnico atribuído ao chamado, se houver, são avisados pelo sino.

### 2.3. Ciclo de vida do chamado

```
ABERTO → ATRIBUIDO → EM_ATENDIMENTO → RESOLVIDO → ENCERRADO
             ▲             │                  │
             │             │                  └──(reabertura)──→ ATRIBUIDO
             └─reatribuição┘

ABERTO | ATRIBUIDO | EM_ATENDIMENTO → CANCELADO
```

`ENCERRADO` e `CANCELADO` são estados finais.

A reatribuição pode ocorrer em `ATRIBUIDO` (o estado não muda) ou em `EM_ATENDIMENTO` (o chamado volta para `ATRIBUIDO` e o atendimento em andamento é encerrado como interrompido). A única outra troca de técnico possível é a transferência obrigatória quando um técnico é inativado, que também alcança chamados em `RESOLVIDO`.

Não existe o estado `REABERTO`. A reabertura é um **evento de negócio**, não uma situação operacional: ela descreve algo que aconteceu, enquanto os estados descrevem onde o chamado está agora. Depois de reaberto, o chamado está operacionalmente atribuído a um técnico — logo, `ATRIBUIDO`. O que registra o evento é a trilha de auditoria e a criação de um novo atendimento, não um estado adicional.

### 2.4. O que caracteriza este sistema como corporativo

| Característica | Como aparece aqui |
|---|---|
| Múltiplos perfis com permissões distintas | Três perfis, com isolamento de dados por contrato |
| Regra de negócio não trivial | Três relógios independentes sobre janela comercial |
| Processamento assíncrono e desacoplado | Derivação de imagens em segundo plano e notificações em picos, tratadas por microsserviço |
| Dados que exigem trilha de auditoria | Operações sensíveis registradas de forma imutável |
| Integração síncrona e assíncrona | REST na entrada, mensageria para o processamento e para notificação |
| Ciclo de vida do dado | Políticas de retenção e expurgo automático |

### 2.5. Decisões de escopo já fechadas

| Decisão | Definição |
|---|---|
| Contrato vencido | Bloqueia a abertura de chamado |
| Política por contrato | Uma única política vigente por contrato |
| Definição de prioridade | Exclusiva do gestor |
| Fase "aguardando cliente" | Não existe; não há pausa de relógio |
| Feriados | Não modelados; não afetam o cálculo |
| Fuso horário | Único para todo o sistema |
| Janela de atendimento | Segunda a sexta, 08h às 17h |
| Mídia aceita | Imagens JPEG, PNG ou WebP, até 10 MB; vídeo fora do escopo |
| Metadados das imagens | EXIF removido na recepção, antes de qualquer gravação |
| Níveis de prioridade | Alta e normal, com prazos distintos para cada relógio |
| Notificação ao cliente | E-mail, em três eventos |
| Notificação ao cliente: destino | E-mail de contato informado no cadastro do cliente |
| Notificação a técnico e gestor | Interna, pelo sino da barra superior; leva ao chamado |
| Notificação de violação | Todos os gestores e o técnico atribuído, se houver |
| Reatribuição | Permitida em `ATRIBUIDO` e `EM_ATENDIMENTO` |
| Identidade | Conta no Keycloak, criada pelo app-principal; vínculo de negócio no app-principal |
| Inativação de técnico | Exige técnico substituto; chamados não finalizados são transferidos a ele |
| Preferência do gestor | Definida no próprio chamado; cada gestor ativa a sua |
| Microsserviço | `ms-notificacoes`, responsável por todas as notificações |
| Entrega de eventos | Padrão outbox: evento gravado na mesma transação do negócio e publicado depois |
| Instância do app-principal | Única |
| Download de evidências | Servido pelo app-principal; o MinIO só é acessível dentro do Docker |
| Estado `REABERTO` | Não existe; reabertura é `RESOLVIDO → ATRIBUIDO` |
| Técnico após reabertura | Mantém o técnico atualmente atribuído |
| Exclusão manual de evidência | Não permitida |
| Retenção das evidências | 180 dias após o chamado atingir um estado final (`ENCERRADO` ou `CANCELADO`) |
| Retenção da trilha de auditoria | 5 anos a partir do evento registrado |
| Expurgo | Exclusivamente automático, por rotina diária |
| Auditoria | Interna; cliente não tem acesso |
| Aplicativo mobile | Fora do escopo; previsto como evolução futura |

---

## 3. Arquitetura em nível de contêineres

Corresponde ao nível 2 do modelo C4. Todos os componentes são empacotados em contêineres Docker.

### 3.1. Componentes

| Contêiner | Responsabilidade | Tecnologia |
|---|---|---|
| **Frontend web** | Interface responsiva para os três perfis; consome a API do app-principal e a API de leitura de notificações do ms-notificacoes (sino da barra superior). Não acessa o MinIO | Livre (React, Angular, Vue ou equivalente) |
| **app-principal** | Monólito modular, em instância única: domínio, regras de SLA, autorização, auditoria, relatórios, rotinas agendadas, provisionamento de contas no Keycloak, processamento assíncrono e download das imagens das evidências. Registra os eventos na tabela de saída (outbox) e os publica no RabbitMQ. Expõe a API REST documentada | Java 21 + Spring Boot 4 |
| **ms-notificacoes** | Microsserviço de notificações: consome os eventos de notificação, envia e-mail ao cliente e grava as notificações internas (sino) de técnico e gestor. Expõe apenas uma API de leitura e marcação de notificações, restrita aos papéis técnico e gestor | Java 21 + Spring Boot 4 |
| **RabbitMQ** | Fila interna de processamento de imagens; exchange de notificações com uma fila por canal (`notificacoes.email` e `notificacoes.interna`); respectivas DLQ | RabbitMQ |
| **PostgreSQL** | Dois bancos lógicos isolados: o do app-principal (domínio, metadados das evidências, auditoria e tabela de saída) e o do ms-notificacoes (notificações internas e controle de eventos processados). Cada serviço versiona o próprio schema com Flyway | PostgreSQL |
| **MinIO** | Object storage das imagens recebidas e derivadas. Acessível apenas pela rede interna do Docker; nunca exposto ao navegador | MinIO (compatível com S3) |
| **Keycloak** | Provedor de identidade externo e fonte oficial dos papéis; guarda as contas, emite os tokens JWT validados pelos dois serviços e envia os e-mails de definição de senha | Keycloak (OAuth 2.1 / OIDC) |
| **Mailpit** | Servidor SMTP de desenvolvimento e de teste; recebe os e-mails do ms-notificacoes e do Keycloak | Mailpit |
| **Toxiproxy** | Proxy TCP usado no teste de carga para injetar latência na conexão SMTP | Toxiproxy |
| **Prometheus + Grafana** | Coleta de métricas dos dois serviços e do RabbitMQ (plugin Prometheus) e painel de observabilidade | Prometheus, Grafana |

### 3.2. Fluxo da evidência ponta a ponta

1. O técnico envia a imagem para `POST /chamados/{id}/evidencias` no **app-principal**
2. O app-principal valida o token, confere se o técnico está atribuído ao chamado, valida o formato (JPEG, PNG ou WebP) e o tamanho
3. Aplica aos pixels a rotação indicada no EXIF e remove todos os metadados EXIF
4. Grava a imagem sanitizada no **MinIO** e calcula o hash SHA-256 sobre essa versão
5. Na mesma transação, persiste a evidência como `RECEBIDA` no **PostgreSQL**, registra a auditoria e grava na tabela de saída o evento de processamento, com a referência ao objeto, nunca o binário
6. Confirma a transação e responde `202 Accepted` ao técnico
7. O publicador da tabela de saída envia o evento à fila interna de processamento (seção 3.6)
8. Um consumidor interno do próprio app-principal, com concorrência limitada, baixa a imagem do MinIO, gera os derivados (miniatura e versões redimensionadas) e os grava no MinIO
9. O consumidor atualiza o estado da evidência para `DISPONIVEL`
10. O download é servido pelo **app-principal**: ele verifica se o usuário pode acessar o chamado, lê o arquivo do MinIO e o transmite ao navegador. Para visualização é servida a versão redimensionada, menor; a imagem recebida só é servida quando pedida explicitamente ou quando os derivados ainda não existem

### 3.3. Por que o processamento de imagens permanece no app-principal

Na primeira entrega, o processamento de evidências era o microsserviço do projeto, porque incluía transcodificação de vídeo — uma operação de segundos de CPU por arquivo. Com a retirada do vídeo do escopo, sobra apenas o processamento de imagem: redimensionar e limpar metadados de uma foto custa centenas de milissegundos, não segundos. Esse custo não justifica mais um contêiner próprio.

A **sanitização** (rotação e remoção do EXIF) acontece de forma síncrona, na recepção, porque precisa ocorrer antes de qualquer gravação: só assim nenhuma coordenada de GPS chega a ser armazenada. Ela exige decodificar e regravar a imagem, o que custa algumas centenas de milissegundos — menos que o próprio envio do arquivo pela rede.

A **geração dos derivados** é **assíncrona**, para que o técnico não espere: a API responde `202 Accepted` e um consumidor interno faz o trabalho depois. A concorrência desse consumidor é **limitada por configuração**, para que um pico de envio de fotos não consuma as threads que atendem a API.

### 3.4. O microsserviço: ms-notificacoes

**O que faz.** Centraliza todas as notificações do sistema:

| Evento | Destinatário | Canal |
|---|---|---|
| Primeiro posicionamento do gestor | Cliente (e-mail de contato do cadastro) | E-mail |
| Chamado marcado como resolvido | Cliente (e-mail de contato do cadastro) | E-mail |
| Aviso de validação automática próxima | Cliente (e-mail de contato do cadastro) | E-mail |
| Chamado atribuído ou reatribuído a um técnico | Técnico atribuído | Notificação interna (sino) |
| Chamado reatribuído | Técnico anterior (aviso de remoção, sem acesso ao chamado) | Notificação interna (sino) |
| Chamado reaberto pelo cliente | Técnico atribuído | Notificação interna (sino) |
| Técnico conclui o atendimento | Gestores que ativaram a preferência naquele chamado | Notificação interna (sino) |
| Violação de SLA (um aviso por relógio violado) | Todos os gestores e o técnico atribuído, se houver | Notificação interna (sino) |

**Quem publica.** O app-principal grava cada evento na tabela de saída, **na mesma transação** da operação que o originou (RN-113), e o publicador o entrega na exchange `notificacoes` (seção 3.6). A exchange roteia o evento para a fila do canal correspondente: `notificacoes.email` ou `notificacoes.interna`. Com uma fila por canal, um problema no envio de e-mail não atrasa as notificações do sino.

Os eventos de violação são gravados pela rotina de detecção de violação, a cada 15 minutos, na mesma transação que marca a violação no chamado.

**Contrato da mensagem.** O ms-notificacoes não consulta o app-principal, então a mensagem carrega tudo o que é necessário para compor a notificação:

| Campo | Conteúdo |
|---|---|
| `eventoId` | Identificador único do evento, gerado ao gravá-lo na tabela de saída. Para violação de SLA, é determinístico: chamado + relógio + marco inicial vigente do relógio |
| `tipo` | Tipo do evento (atribuição, reatribuição, remoção, reabertura, conclusão, violação, posicionamento, resolução, aviso de encerramento) |
| `chamado` | Identificador interno e número legível do chamado (ex.: `#1042`) |
| `titulo` | Título do chamado |
| `destinatarios` | `sub` do Keycloak nas notificações internas; e-mail de contato do cliente nos e-mails |
| `textoPosicionamento` | Apenas no e-mail de primeiro posicionamento: o texto registrado pelo gestor |

O `eventoId` determinístico da violação garante que a mesma violação nunca gere dois avisos, mesmo que a rotina rode de novo sobre o mesmo chamado. O marco inicial entra no identificador porque a reabertura reinicia os relógios: uma nova violação depois de uma reabertura é outro evento e precisa gerar outro aviso.

**Quem consome.** O **ms-notificacoes**, com um consumidor por fila:

- **E-mail:** envio ao provedor SMTP, protegido por timeout, retry e circuit breaker (Resilience4j)
- **Notificação interna:** gravação no banco próprio do microsserviço, de onde o sino a lê

**Como o sino funciona.** O frontend consulta periodicamente a API de leitura do ms-notificacoes (polling), que retorna as notificações do usuário autenticado — com número e título do chamado — e a contagem de não lidas. A API aceita apenas os papéis técnico e gestor. Ao clicar, o usuário é levado ao chamado correspondente e a notificação é marcada como lida. A API valida o mesmo token JWT emitido pelo Keycloak, e o usuário só enxerga as próprias notificações, identificado pelo `sub` do token, nunca por parâmetro da requisição. A autorização de acesso ao chamado continua sendo do app-principal: se o usuário não tiver mais acesso (por exemplo, após uma reatribuição), ele vê um aviso, sem dados do chamado.

**Dono dos próprios dados.** O ms-notificacoes tem banco lógico exclusivo. Ele não acessa o banco do app-principal e o app-principal não acessa o dele. Tudo o que o microsserviço precisa chega na mensagem. É isso que permite implantá-lo, escalá-lo e derrubá-lo de forma independente.

**O que acontece se falhar.**

| Cenário | Tratamento |
|---|---|
| Falha temporária no envio de e-mail (timeout, resposta SMTP 4xx) | Nova tentativa com backoff exponencial, até 3 vezes |
| Falha definitiva no envio de e-mail (resposta SMTP 5xx, como endereço inválido) | Encaminhada direto à DLQ, sem novas tentativas, porque repetir não resolve |
| Provedor SMTP fora do ar | O circuit breaker abre e o consumidor de e-mail é **pausado**. As mensagens aguardam na fila sem consumir tentativas. Quando o circuito volta a fechar, o consumo é retomado e a fila drena. O sino continua funcionando normalmente, porque tem fila própria |
| Falha ao gravar notificação interna (banco momentaneamente indisponível) | Nova tentativa com backoff exponencial, até 3 vezes; esgotadas, vai para a DLQ |
| Evento duplicado | O microsserviço registra o identificador de cada evento **após** o processamento bem-sucedido e descarta repetições |
| Microsserviço fora do ar | Os eventos acumulam nas filas e são processados quando ele volta. Nenhuma operação do app-principal é bloqueada |
| RabbitMQ fora do ar | Os eventos permanecem pendentes na tabela de saída do app-principal e são publicados quando o broker volta. Nenhum evento é perdido |
| Mensagens na DLQ | Expiram em 7 dias. O reprocessamento é uma operação de manutenção (devolução à fila de origem pelo painel do RabbitMQ), não uma funcionalidade do sistema |

**Por que ele escala separado.** O envio de notificações não é intensivo em CPU: é intensivo em **espera de rede**. Cada e-mail passa a maior parte do tempo aguardando o servidor SMTP responder. A carga também chega em **picos**, gerados pelas rotinas de prazo:

- A **rotina de violação**, a cada 15 minutos, pode marcar vários chamados de uma vez, e cada relógio violado gera avisos para todos os gestores e para o técnico responsável
- A **rotina diária de validação automática** dispara de uma vez os avisos de encerramento próximo aos clientes

Trabalho limitado por espera de rede escala com concorrência: se cada envio leva ~300 ms aguardando o provedor, quatro instâncias drenam a fila perto de quatro vezes mais rápido que uma. O microsserviço tem as propriedades que permitem isso:

- **Sem estado em memória.** Qualquer instância processa qualquer evento
- **Idempotente.** Duas instâncias recebendo o mesmo evento não geram notificação duplicada
- **Concorrência de consumidores nativa no RabbitMQ.** Novas instâncias passam a consumir das mesmas filas sem configuração extra

**Como será comprovado.** O teste de carga gera um pico de eventos de notificação e mede o tempo de drenagem da fila de e-mail com 1 e com N instâncias. Um **Toxiproxy** é colocado entre o microsserviço e o Mailpit para injetar latência na conexão SMTP, porque sem ela cada envio local leva poucos milissegundos, a fila nunca acumula e o teste não mede nada. Essa latência simula o comportamento de um provedor real e é declarada como premissa do teste.

**Demonstração de resiliência.** Derrubar o Mailpit durante o uso: o circuito abre, a fila de e-mail acumula, o sino continua recebendo notificações; ao subir o Mailpit de novo, a fila drena sem perda de mensagens.

### 3.5. Identidade e provisionamento de usuários

A **conta** do usuário vive no Keycloak; o **vínculo de negócio** vive no app-principal.

- **Cadastro.** Quando o gestor cadastra um usuário, o app-principal cria a conta no Keycloak pela Admin API e atribui o papel (`CLIENTE`, `TECNICO` ou `GESTOR`). O Keycloak envia ao usuário o e-mail para definir a senha. A senha nunca passa pelo sistema nem é armazenada por ele.
- **Registro local.** O app-principal guarda o `sub` do Keycloak, o nome, uma cópia do perfil e, para usuários do perfil cliente, a empresa cliente a que pertencem. O vínculo com a empresa é dado de domínio e não vai no token.
- **Fonte oficial do papel.** O Keycloak é a fonte oficial: a autorização usa sempre o papel presente no token. A cópia local do perfil é gravada somente pelo app-principal, no cadastro, e serve apenas para consultas — por exemplo, listar os gestores que devem receber um aviso de violação. Papéis só são alterados pelo app-principal; alterações feitas diretamente no console do Keycloak não são suportadas.
- **Identificador único.** O `sub` é o identificador do usuário em todo o sistema: atribuição de chamados, trilha de auditoria e destinatário das notificações. É por ele que o ms-notificacoes associa a notificação gravada ao usuário que consulta o sino.
- **Inativação.** Inativar um usuário desabilita a conta no Keycloak, bloqueando o login, e marca o registro local como inativo. O usuário nunca é excluído, porque a auditoria e os chamados apontam para ele.
- **Técnico substituto.** Ao inativar um técnico com chamados não finalizados, o gestor indica um técnico substituto ativo, e todos esses chamados são transferidos na mesma transação: os que estão em `ATRIBUIDO` passam ao substituto; os que estão em `EM_ATENDIMENTO` voltam para `ATRIBUIDO` com o substituto; os que estão em `RESOLVIDO` mantêm o estado, mas passam a ter o substituto como técnico responsável, para que uma eventual reabertura chegue a quem pode atendê-la.
- **Consistência.** A conta é criada primeiro no Keycloak e depois no banco. Se a gravação no banco falhar, o app-principal remove a conta recém-criada no Keycloak (compensação), evitando conta sem vínculo.
- **Resiliência e credenciais.** A Admin API do Keycloak é uma chamada externa síncrona, protegida por timeout e retry. O app-principal a acessa com uma conta de serviço própria, cujas credenciais ficam em variáveis de ambiente.
- **E-mail de definição de senha.** É enviado pelo próprio Keycloak, configurado com o mesmo servidor SMTP do sistema.

### 3.6. Entrega confiável de eventos (outbox)

**O problema.** Publicar o evento no RabbitMQ logo após o commit evita mensagem para um registro que não existe, mas abre o risco oposto: se o broker estiver fora do ar, ou a aplicação cair entre o commit e a publicação, o evento se perde sem que ninguém perceba. A notificação nunca sai, ou a evidência fica em `RECEBIDA` para sempre.

**A solução.** O app-principal mantém uma **tabela de saída** (`outbox_eventos`) no próprio banco:

1. Toda operação que gera um evento grava esse evento na tabela de saída **na mesma transação** da operação de negócio. Se a transação falhar, o evento não existe; se confirmar, o evento está garantido.
2. Um **publicador** do app-principal lê os eventos pendentes a cada poucos segundos, em ordem de criação, e os publica no RabbitMQ com confirmação do broker (*publisher confirms*).
3. Só depois da confirmação o evento é marcado como publicado. Se o broker estiver indisponível, o evento continua pendente e é tentado de novo no ciclo seguinte.

**Consequência: entrega ao menos uma vez.** Se a aplicação cair depois de publicar e antes de marcar o evento, ele será publicado de novo. Por isso, todos os consumidores são idempotentes: o ms-notificacoes descarta eventos repetidos pelo `eventoId`, e o processamento de imagens descarta evidências já `DISPONIVEL`.

**Abrangência.** Vale para os dois fluxos assíncronos: processamento de imagens e notificações. Eventos já publicados são removidos da tabela de saída após 7 dias pela Rotina de Retenção e Expurgo.

**Instância única.** O app-principal executa em uma única instância. Com isso, o publicador e as rotinas agendadas rodam sem disputa entre instâncias e sem trava distribuída. Escalar o app-principal horizontalmente exigiria essa trava (por exemplo, ShedLock), registrada como evolução futura.

### 3.7. Revisão de arquitetura após a primeira entrega

| | Primeira entrega | Revisão atual |
|---|---|---|
| Microsserviço | `ms-evidencias` (processamento de mídia) | `ms-notificacoes` (notificações) |
| Mídia aceita | Imagem e vídeo | Apenas imagem |
| Processamento de evidências | Microsserviço dedicado | Consumidor assíncrono interno do app-principal |
| Notificações | E-mail ao cliente, consumido no app-principal | E-mail ao cliente e notificações internas a técnico e gestor (atribuição, reabertura, conclusão e violação), no microsserviço |
| Argumento de escala | Processamento intensivo em CPU | Processamento intensivo em espera de rede, com carga em picos |

**Motivação.** Após a apresentação, a equipe concluiu que a transcodificação de vídeo concentrava o maior risco técnico do projeto (ffmpeg, arquivos grandes, tempo de processamento) sem ser central ao problema de negócio: uma foto já comprova o atendimento. Ao mesmo tempo, a necessidade de notificar técnico e gestor ampliou o papel das notificações, que passaram a ter volume, canais distintos e domínio de falha próprio — características adequadas a um serviço independente.

**Consequências assumidas.** O argumento de escalabilidade passa a ser mais fraco que o anterior, porque não há gargalo de CPU, e depende de latência de rede simulada no teste (Toxiproxy). O processamento de imagens passa a competir por recursos com a API, mitigado pela concorrência limitada. A decisão é registrada em ADR de revisão.

---

## 4. Requisitos funcionais

Identificados como `RF-XX`. A coluna **Perfil** indica quem executa.

### 4.1. Acesso e identidade

| ID | Requisito | Perfil |
|---|---|---|
| RF-01 | Autenticar-se via provedor de identidade externo (Keycloak), recebendo token com papel | Todos |
| RF-02 | Ter o acesso aos endpoints restringido conforme o papel do token | Todos |
| RF-03 | Visualizar apenas os dados vinculados aos contratos do próprio cliente | Cliente |
| RF-04 | Visualizar apenas os chamados atribuídos a si, sem acesso a dados comerciais | Técnico |

### 4.2. Cadastros

| ID | Requisito | Perfil |
|---|---|---|
| RF-05 | Cadastrar, editar e inativar clientes, informando razão social, e-mail de contato e endereço | Gestor |
| RF-06 | Cadastrar contratos com vigência (início e fim) vinculados a um cliente | Gestor |
| RF-07 | Cadastrar políticas de SLA definindo prazos por prioridade para os três relógios | Gestor |
| RF-08 | Vincular uma política de SLA vigente a um contrato | Gestor |
| RF-09 | Cadastrar usuários, criando a conta no Keycloak com o papel correspondente e, para o perfil cliente, vinculando-os à empresa cliente | Gestor |
| RF-10 | Inativar usuários, bloqueando o login no Keycloak e preservando o registro; ao inativar um técnico com chamados não finalizados, indicar o técnico substituto | Gestor |
| RF-11 | Consultar o histórico de versões de uma política de SLA | Gestor |

### 4.3. Chamados

| ID | Requisito | Perfil |
|---|---|---|
| RF-12 | Abrir chamado informando título, descrição, local e prioridade sugerida | Cliente |
| RF-13 | Consultar os próprios chamados com status e prazos | Cliente |
| RF-14 | Consultar todos os chamados com filtros por status, cliente, técnico e prioridade | Gestor |
| RF-15 | Definir ou alterar a prioridade definitiva do chamado | Gestor |
| RF-16 | Registrar o primeiro posicionamento ao cliente | Gestor |
| RF-17 | Atribuir o chamado a um técnico, ou reatribuí-lo enquanto estiver em `ATRIBUIDO` ou `EM_ATENDIMENTO` | Gestor |
| RF-18 | Cancelar um chamado com motivo obrigatório | Gestor |
| RF-19 | Consultar os chamados atribuídos a si | Técnico |
| RF-20 | Validar um chamado resolvido, encerrando-o | Cliente |
| RF-21 | Reabrir um chamado resolvido com justificativa obrigatória | Cliente |

### 4.4. Atendimento e evidências

| ID | Requisito | Perfil |
|---|---|---|
| RF-22 | Registrar o início do atendimento presencial | Técnico |
| RF-23 | Registrar a descrição técnica do que foi executado | Técnico |
| RF-24 | Anexar evidências fotográficas em JPEG, PNG ou WebP, de até 10 MB | Técnico |
| RF-25 | Receber confirmação imediata do envio da evidência, sem aguardar o processamento | Técnico |
| RF-26 | Consultar o status de processamento de cada evidência | Técnico / Gestor |
| RF-27 | Concluir o atendimento, exigindo ao menos uma evidência anexada | Técnico |
| RF-28 | Visualizar as evidências de um chamado, servidas pelo app-principal após verificação de acesso | Cliente / Gestor / Técnico |
| RF-29 | Solicitar o reprocessamento manual de uma evidência que falhou | Gestor |

### 4.5. SLA e apuração

| ID | Requisito | Perfil |
|---|---|---|
| RF-30 | Calcular e gravar os três prazos no momento da abertura do chamado | Sistema |
| RF-31 | Recalcular os prazos ainda não cumpridos quando a prioridade for alterada, a partir do marco inicial vigente de cada relógio | Sistema |
| RF-32 | Recalcular os prazos de atendimento em campo e de resolução na reabertura, usando a data e hora da reabertura como marco | Sistema |
| RF-33 | Registrar a violação de cada SLA no instante em que o prazo é ultrapassado | Sistema |
| RF-34 | Executar rotina que varre chamados abertos, marca prazos vencidos e registra os eventos de violação, a cada 15 minutos | Sistema |
| RF-35 | Executar rotina diária que valida automaticamente chamados resolvidos há 3 dias úteis e envia o aviso prévio | Sistema |
| RF-36 | Congelar a apuração dos três SLAs no encerramento do chamado | Sistema |
| RF-37 | Consultar o resultado de SLA de um chamado encerrado | Todos |

### 4.6. Relatórios e auditoria

| ID | Requisito | Perfil |
|---|---|---|
| RF-38 | Gerar relatório gerencial de cumprimento de SLA por período, cliente e técnico | Gestor |
| RF-39 | Exportar o relatório gerencial em PDF ou CSV | Gestor |
| RF-40 | Consultar a trilha de auditoria com filtros por usuário, entidade e período | Gestor |
| RF-41 | Registrar automaticamente as operações sensíveis na trilha de auditoria | Sistema |

### 4.7. Retenção e expurgo

| ID | Requisito | Perfil |
|---|---|---|
| RF-42 | Executar a **Rotina de Retenção e Expurgo** diariamente | Sistema |
| RF-43 | Localizar evidências de chamados encerrados ou cancelados há mais de 180 dias e expurgar seu conteúdo do object storage | Sistema |
| RF-44 | Localizar registros de auditoria com mais de 5 anos e removê-los conforme a política de retenção | Sistema |
| RF-45 | Registrar cada expurgo de evidência na trilha de auditoria | Sistema |
| RF-46 | Consultar os metadados de uma evidência já expurgada, para fins de rastreabilidade | Gestor |
| RF-47 | Remover do object storage os objetos órfãos, gravados sem registro correspondente no banco | Sistema |
| RF-48 | Remover da tabela de saída os eventos publicados há mais de 7 dias | Sistema |

### 4.8. Notificações e entrega de eventos

| ID | Requisito | Perfil |
|---|---|---|
| RF-49 | Registrar na tabela de saída, na mesma transação da operação de negócio, cada evento de notificação e de processamento de imagem | Sistema |
| RF-50 | Publicar no RabbitMQ os eventos pendentes da tabela de saída, marcando-os como publicados somente após a confirmação do broker | Sistema |
| RF-51 | Rotear cada evento de notificação, pela exchange `notificacoes`, para a fila do canal correspondente | Sistema |
| RF-52 | Enviar ao e-mail de contato do cliente o e-mail correspondente ao evento (posicionamento, resolução, aviso de encerramento automático), com nova tentativa em caso de falha temporária | Sistema |
| RF-53 | Receber notificação interna ao ser atribuído ou reatribuído a um chamado | Técnico |
| RF-54 | Receber notificação interna quando um chamado atribuído a si for reaberto pelo cliente | Técnico |
| RF-55 | Receber aviso interno ao ser removido de um chamado por reatribuição | Técnico |
| RF-56 | Ativar ou desativar, no chamado, a preferência de ser notificado quando o técnico concluir o atendimento | Gestor |
| RF-57 | Receber notificação interna quando o técnico concluir o atendimento de um chamado em que a preferência está ativa | Gestor |
| RF-58 | Receber notificação interna quando um prazo de SLA for violado | Gestor / Técnico atribuído |
| RF-59 | Visualizar no sino da barra superior as próprias notificações e a contagem de não lidas | Técnico / Gestor |
| RF-60 | Ser direcionado ao chamado ao clicar em uma notificação, que passa a constar como lida | Técnico / Gestor |
| RF-61 | Encaminhar à DLQ e registrar o evento cujo processamento falhou definitivamente | Sistema |
| RF-62 | Remover automaticamente, em rotina diária do ms-notificacoes, as notificações internas com mais de 90 dias | Sistema |

### 4.9. Observabilidade

| ID | Requisito | Perfil |
|---|---|---|
| RF-63 | Expor health checks e métricas da aplicação e do microsserviço | Sistema |
| RF-64 | Expor métricas de fila — profundidade, taxa de consumo e mensagens em DLQ, incluindo as filas de notificação — e a quantidade de eventos pendentes na tabela de saída | Sistema |
| RF-65 | Expor métricas do ms-notificacoes: eventos processados, falhas por canal e estado do circuit breaker | Sistema |

---

## 5. Regras de negócio

Identificadas como `RN-XX`. São as regras que o código precisa garantir, independentemente da interface.

### 5.1. Contrato e política

| ID | Regra |
|---|---|
| RN-01 | Um chamado só pode ser aberto sob contrato com vigência ativa na data de abertura |
| RN-02 | Um contrato possui exatamente uma política de SLA vigente por vez |
| RN-03 | Contrato sem política vinculada não pode receber chamados |
| RN-04 | Alterar uma política de SLA gera uma nova versão; a versão anterior permanece imutável |
| RN-05 | Chamados já abertos permanecem vinculados à versão da política vigente na sua abertura, inclusive após reabertura |
| RN-06 | Um cliente pode ter múltiplos contratos simultâneos |
| RN-07 | Inativar um cliente não remove seus chamados históricos |
| RN-08 | O cadastro do cliente contém razão social, e-mail de contato e endereço; o e-mail de contato é o destinatário de todas as notificações por e-mail daquele cliente |

### 5.2. Chamado e ciclo de vida

| ID | Regra |
|---|---|
| RN-09 | Os estados válidos são: `ABERTO`, `ATRIBUIDO`, `EM_ATENDIMENTO`, `RESOLVIDO`, `ENCERRADO`, `CANCELADO` |
| RN-10 | As transições válidas são: `ABERTO → ATRIBUIDO`, `ATRIBUIDO → EM_ATENDIMENTO`, `EM_ATENDIMENTO → RESOLVIDO`, `RESOLVIDO → ENCERRADO`, `RESOLVIDO → ATRIBUIDO` (reabertura), `EM_ATENDIMENTO → ATRIBUIDO` (reatribuição) e `ABERTO`, `ATRIBUIDO` ou `EM_ATENDIMENTO` para `CANCELADO` |
| RN-11 | `ENCERRADO` e `CANCELADO` são estados finais: não aceitam nenhuma transição, inclusive reabertura |
| RN-12 | Não existe o estado `REABERTO`: a reabertura é um evento de negócio registrado na auditoria, não uma situação operacional do chamado |
| RN-13 | Transições fora da máquina de estados são rejeitadas como erro de negócio |
| RN-14 | Cada chamado recebe, na abertura, um número sequencial legível (ex.: `#1042`), usado na interface e nas notificações, além do identificador interno |
| RN-15 | A prioridade sugerida pelo cliente não tem efeito no cálculo; apenas a prioridade definida pelo gestor vale |
| RN-16 | Enquanto o gestor não definir a prioridade, o chamado usa a prioridade padrão da política |
| RN-17 | Alterar a prioridade recalcula os prazos ainda não cumpridos, sempre a partir do marco inicial vigente do respectivo relógio |
| RN-18 | Prazos já cumpridos ou já violados não são recalculados |
| RN-19 | Um chamado cancelado não gera apuração de SLA; cancelamento exige motivo e só é permitido antes do estado `RESOLVIDO` |
| RN-20 | A reatribuição pelo gestor só é permitida nos estados `ATRIBUIDO` e `EM_ATENDIMENTO`; a transferência por inativação de técnico (RN-133) é a única exceção |
| RN-21 | Reatribuir um chamado em `EM_ATENDIMENTO` o devolve para `ATRIBUIDO` e encerra o atendimento em andamento como interrompido, preservando suas evidências |
| RN-22 | A reatribuição não reinicia nem recalcula prazos; um SLA já cumprido permanece cumprido. O SLA é um compromisso da prestadora com o cliente, não de cada técnico: trocar o técnico é uma reorganização interna e não cria nova obrigação de chegada. Um atraso causado pela troca é medido pelo SLA de resolução, que continua correndo |

### 5.3. Os três relógios de SLA

| ID | Regra |
|---|---|
| RN-23 | O **SLA de resposta** inicia na abertura e é cumprido pelo primeiro posicionamento registrado pelo gestor |
| RN-24 | O **SLA de atendimento em campo** inicia na abertura e é cumprido pelo primeiro registro de atendimento presencial do técnico |
| RN-25 | O **SLA de resolução** inicia na abertura e é cumprido quando o chamado passa a `RESOLVIDO` |
| RN-26 | Atribuir um chamado a um técnico não cumpre nenhum SLA |
| RN-27 | Os três relógios são independentes na violação: cada um pode ser violado sem afetar os demais |
| RN-28 | Cada relógio tem prazo próprio por prioridade, definido na política |

### 5.4. Prazos por prioridade

A política padrão do sistema usa a matriz abaixo. Como a janela de atendimento tem 9 horas por dia (08h às 17h), um dia útil equivale a 540 minutos de relógio. Os prazos são armazenados em **minutos de janela** e convertidos para "dias úteis" apenas na interface.

| Relógio | Alta | Normal |
|---|---|---|
| Resposta ao cliente | 2 h (120 min) | 1 dia útil (540 min) |
| Atendimento em campo | 1 dia útil (540 min) | 3 dias úteis (1620 min) |
| Resolução | 2 dias úteis (1080 min) | 5 dias úteis (2700 min) |

Em linguagem de contrato: um chamado de prioridade alta é reconhecido em 2 horas, tem técnico no local até o fim do dia útil seguinte e é resolvido em até 2 dias úteis.

| ID | Regra |
|---|---|
| RN-29 | Os prazos são armazenados e calculados em minutos de janela, nunca em dias corridos |
| RN-30 | Ao configurar uma política, o prazo de resposta deve ser menor que o de atendimento em campo, e o de atendimento em campo menor que o de resolução. Essa ordem vale apenas para os prazos configurados: na prática, os eventos podem acontecer em qualquer ordem — por exemplo, o técnico pode chegar ao local antes de o gestor registrar o primeiro posicionamento |
| RN-31 | O prazo de resposta é sempre o mais curto dos três, por medir um reconhecimento e não um deslocamento |
| RN-32 | Ao cadastrar ou versionar uma política, o sistema valida que a ordem de RN-30 é respeitada em cada prioridade; política que viole a ordem é rejeitada |

### 5.5. Notificações

| ID | Regra |
|---|---|
| RN-33 | O cliente é notificado por e-mail em três eventos: primeiro posicionamento do gestor, chamado marcado como resolvido, e proximidade da validação automática |
| RN-34 | O aviso de validação automática é enviado 1 dia útil antes do encerramento automático |
| RN-35 | O técnico recebe notificação interna sempre que um chamado é atribuído ou reatribuído a ele |
| RN-36 | O gestor recebe notificação interna quando o técnico conclui o atendimento, somente se tiver ativado a preferência de notificação naquele chamado |
| RN-37 | Na reatribuição, o técnico anterior recebe um aviso interno de remoção, sem acesso ao chamado |
| RN-38 | O técnico atribuído recebe notificação interna quando o chamado é reaberto pelo cliente |
| RN-39 | Cada relógio de SLA violado gera notificação interna para todos os gestores e para o técnico atribuído ao chamado, se houver, independentemente da preferência de notificação |
| RN-40 | A preferência de notificação é individual por gestor e por chamado: cada gestor ativa ou desativa a sua, a qualquer momento, e só quem ativou é notificado |
| RN-41 | Técnico e gestor não recebem e-mail; cliente não recebe notificação interna |
| RN-42 | Cada notificação interna leva ao chamado relacionado e passa a constar como lida quando aberta pelo destinatário |
| RN-43 | O usuário só consulta as próprias notificações; o destinatário é identificado pelo `sub` do token, nunca por parâmetro da requisição |
| RN-44 | A API de notificações aceita apenas os papéis técnico e gestor |
| RN-45 | Notificação que aponta para chamado ao qual o usuário não tem mais acesso exibe apenas um aviso, sem dados do chamado; a autorização é sempre verificada pelo app-principal |
| RN-46 | Toda notificação é assíncrona e desacoplada por mensageria: a operação de negócio registra o evento e retorna imediatamente, sem aguardar a entrega |
| RN-47 | A chamada ao provedor de e-mail é protegida por timeout, retry e circuit breaker |
| RN-48 | Cada canal possui fila própria (`notificacoes.email` e `notificacoes.interna`); falhas em um canal não atrasam o outro |
| RN-49 | Com o circuit breaker do SMTP aberto, o consumo da fila de e-mail é pausado e as mensagens aguardam sem consumir tentativas; o consumo é retomado quando o circuito fecha |
| RN-50 | Falha temporária (timeout ou resposta SMTP 4xx) gera nova tentativa com backoff, até 3 vezes; falha definitiva (resposta SMTP 5xx) vai direto para a DLQ |
| RN-51 | Falha definitiva na notificação é registrada e não desfaz a operação de negócio que a originou |
| RN-52 | O evento de notificação é gravado na tabela de saída na mesma transação da operação que o originou; nunca é publicado diretamente pela operação de negócio |
| RN-53 | A mensagem carrega: identificador do evento (`eventoId`), tipo do evento, identificador interno e número legível do chamado, título do chamado e destinatários — o `sub` do Keycloak nas notificações internas e o e-mail de contato do cliente nos e-mails. O e-mail de primeiro posicionamento carrega também o texto do posicionamento |
| RN-54 | O `eventoId` é gerado na gravação do evento na tabela de saída; para violação de SLA, é determinístico, composto por chamado, relógio e marco inicial vigente do relógio, de modo que a mesma violação nunca gera dois avisos e uma nova violação após reabertura gera um novo aviso |
| RN-55 | O ms-notificacoes registra o identificador de cada evento somente após o processamento bem-sucedido e descarta repetições, evitando notificação duplicada |
| RN-56 | Esgotadas as tentativas, a mensagem é encaminhada à fila de mensagens mortas do canal (DLQ) |
| RN-57 | Mensagens nas DLQ de notificação expiram em 7 dias; o reprocessamento é operação de manutenção, não funcionalidade do sistema |
| RN-58 | O ms-notificacoes possui banco lógico exclusivo e não acessa o banco do app-principal nem consulta sua API; tudo de que precisa para compor a notificação chega na mensagem |
| RN-59 | Notificações internas são mantidas por 90 dias a partir da criação e removidas automaticamente pelo próprio ms-notificacoes |

### 5.6. Contagem de tempo

| ID | Regra |
|---|---|
| RN-60 | O tempo só é consumido dentro da janela de atendimento: segunda a sexta, das 08h às 17h |
| RN-61 | Chamado aberto fora da janela começa a consumir tempo na próxima abertura da janela |
| RN-62 | Todo o sistema opera em um único fuso horário |
| RN-63 | Feriados não são tratados: um feriado é contado como dia útil normal |
| RN-64 | Os prazos são calculados e gravados no chamado; não são recalculados a cada consulta |

### 5.7. Violação e apuração

| ID | Regra |
|---|---|
| RN-65 | A violação ocorre no instante em que o prazo é ultrapassado sem o evento correspondente |
| RN-66 | A violação é detectada mesmo com o chamado ainda aberto, por rotina que roda a cada 15 minutos |
| RN-67 | Cada violação registra qual relógio foi violado e o horário real do estouro, não o horário da detecção |
| RN-68 | A apuração dos três SLAs é congelada no encerramento e não muda depois |
| RN-69 | Um SLA cumprido após a violação continua registrado como violado |

### 5.8. Atendimento e evidências

| ID | Regra |
|---|---|
| RN-70 | Um chamado pode ter múltiplos atendimentos; cada reabertura gera um novo |
| RN-71 | Toda evidência pertence a um atendimento, nunca diretamente ao chamado |
| RN-72 | Concluir um atendimento exige ao menos uma evidência anexada |
| RN-73 | Evidência em estado `RECEBIDA` já satisfaz RN-72; não é necessário aguardar o processamento |
| RN-74 | Somente o técnico atribuído ao chamado pode anexar evidências a ele |
| RN-75 | São aceitas apenas imagens JPEG, PNG ou WebP, até 10 MB; formato e tamanho são validados na recepção, antes de gravar no storage |
| RN-76 | Os metadados EXIF são removidos na recepção, antes de qualquer gravação; a rotação indicada no EXIF é aplicada aos pixels antes da remoção |
| RN-77 | O hash SHA-256 é calculado na recepção, sobre a imagem já sanitizada, e é imutável |
| RN-78 | A imagem armazenada na recepção nunca é alterada; o processamento gera apenas derivados |
| RN-79 | Evidências não podem ser excluídas manualmente por nenhum perfil de usuário. Sua remoção somente pode ocorrer por meio da política automática de expurgo após o término do período de retenção |
| RN-80 | O download de evidência é servido pelo app-principal, que verifica a autorização, lê o arquivo do MinIO e o transmite ao navegador; o MinIO não é acessível pelo navegador |
| RN-81 | Para visualização é servida a versão redimensionada da imagem; a imagem recebida só é servida quando solicitada explicitamente ou enquanto os derivados não estiverem disponíveis |

### 5.9. Processamento assíncrono de imagens

| ID | Regra |
|---|---|
| RN-82 | Os estados da evidência são: `RECEBIDA`, `PROCESSANDO`, `DISPONIVEL`, `FALHA`, `EXPURGADA` |
| RN-83 | A API responde ao envio após a sanitização e a gravação, sem aguardar a geração dos derivados |
| RN-84 | O processamento de imagens é executado por consumidor interno do app-principal, com concorrência limitada por configuração para não competir com o atendimento da API |
| RN-85 | A mensagem publicada carrega apenas a referência ao objeto, nunca o binário |
| RN-86 | O processamento é idempotente: evidência já `DISPONIVEL` é descartada sem reprocessar |
| RN-87 | Falha transitória gera nova tentativa com backoff, até 3 vezes |
| RN-88 | Esgotadas as tentativas, a mensagem vai para a DLQ e a evidência fica em `FALHA` |
| RN-89 | Evidência em `FALHA` não desfaz a conclusão do atendimento |
| RN-90 | O reconhecimento da mensagem ocorre somente após a persistência do resultado |

### 5.10. Validação, reabertura e encerramento

| ID | Regra |
|---|---|
| RN-91 | Somente o cliente vinculado ao contrato pode validar ou reabrir o chamado |
| RN-92 | Chamado resolvido sem resposta do cliente por 3 dias úteis é validado automaticamente e passa a `ENCERRADO` |
| RN-93 | A reabertura só pode ocorrer a partir do estado `RESOLVIDO` e altera o estado do chamado para `ATRIBUIDO` |
| RN-94 | A reabertura mantém o técnico atualmente atribuído; a troca de técnico usa o fluxo existente de reatribuição |
| RN-95 | Cada reabertura cria um novo atendimento vinculado ao mesmo chamado |
| RN-96 | A reabertura reinicia os relógios de atendimento em campo e de resolução, usando a data e hora da reabertura como novo marco inicial |
| RN-97 | A reabertura não reinicia nem recalcula o SLA de resposta, cuja obrigação de primeiro posicionamento já foi cumprida no ciclo original |
| RN-98 | A reabertura exige justificativa obrigatória do cliente |
| RN-99 | Chamado `ENCERRADO` não aceita novas alterações nem reabertura |

### 5.11. Retenção e expurgo

O sistema possui uma **Rotina de Retenção e Expurgo** com execução diária e quatro responsabilidades: expurgar o conteúdo de evidências vencidas, remover registros de auditoria expirados, remover objetos órfãos do storage e limpar a tabela de saída.

```
Job de retenção → evidências vencidas  → expurgo do object storage
Job de retenção → auditorias vencidas  → expurgo dos registros
Job de retenção → objetos órfãos       → remoção do object storage
Job de retenção → eventos publicados   → limpeza da tabela de saída
```

| ID | Regra |
|---|---|
| RN-100 | As evidências são mantidas por 180 dias contados a partir do momento em que o chamado atinge um estado final: `ENCERRADO` ou `CANCELADO` |
| RN-101 | A contagem do prazo de retenção inicia somente quando o chamado atinge `ENCERRADO` ou `CANCELADO`; nos estados `ABERTO`, `ATRIBUIDO`, `EM_ATENDIMENTO` e `RESOLVIDO` a evidência não é elegível para expurgo |
| RN-102 | Terminado o período de retenção, a Rotina de Retenção e Expurgo remove do object storage a imagem armazenada na recepção e todos os derivados: miniaturas, versões redimensionadas e quaisquer outros artefatos gerados pelo processamento |
| RN-103 | Após o expurgo, nenhum arquivo da evidência permanece no object storage |
| RN-104 | O registro da evidência não é removido do banco: preservam-se os metadados mínimos de rastreabilidade — identificador da evidência, atendimento e chamado relacionados, data do envio e data do expurgo — e o estado passa a `EXPURGADA` |
| RN-105 | Todo expurgo de evidência gera registro na trilha de auditoria contendo, no mínimo, identificador da evidência, chamado relacionado, data e hora do expurgo e o motivo `EXPIRACAO_RETENCAO` |
| RN-106 | Os registros da trilha de auditoria são mantidos por 5 anos contados da data e hora do evento registrado |
| RN-107 | Registros de auditoria cujo período de retenção tenha expirado podem ser removidos exclusivamente pela Rotina de Retenção e Expurgo |
| RN-108 | Nenhum perfil do sistema — cliente, técnico ou gestor — dispõe de operação manual para remover evidências ou registros de auditoria |
| RN-109 | Objetos gravados no storage sem registro correspondente no banco há mais de 24 horas são removidos pela Rotina de Retenção e Expurgo |
| RN-110 | Eventos da tabela de saída já publicados há mais de 7 dias são removidos pela Rotina de Retenção e Expurgo |

### 5.12. Integridade transacional e concorrência

| ID | Regra |
|---|---|
| RN-111 | A abertura do chamado — persistência, cálculo dos três prazos e registro de auditoria — ocorre em uma única transação |
| RN-112 | A recepção da evidência grava o objeto no storage antes de abrir a transação de banco; se a transação falhar, o objeto órfão é removido pela Rotina de Retenção e Expurgo (RN-109) |
| RN-113 | Todo evento destinado à mensageria é gravado na tabela de saída (outbox) na mesma transação da operação de negócio; se a transação falhar, o evento não existe |
| RN-114 | O publicador lê os eventos pendentes em ordem de criação, publica no RabbitMQ com confirmação do broker e só então os marca como publicados; com o broker indisponível, o evento permanece pendente e é tentado no ciclo seguinte |
| RN-115 | A entrega é ao menos uma vez: um evento pode ser publicado mais de uma vez, e por isso todos os consumidores são idempotentes (RN-55, RN-86) |
| RN-116 | O app-principal executa em instância única; as rotinas agendadas e o publicador da tabela de saída não usam trava distribuída |
| RN-117 | A atribuição de um chamado usa bloqueio otimista: duas atribuições concorrentes fazem a segunda falhar com conflito explícito |
| RN-118 | A conclusão de um atendimento verifica a existência de evidência dentro da mesma transação que muda o estado |
| RN-119 | As rotinas agendadas processam em lotes, com transação por lote, para não manter transação longa aberta |
| RN-120 | O registro de auditoria participa da transação da operação que o originou |

### 5.13. Auditoria e acesso

| ID | Regra |
|---|---|
| RN-121 | Geram registro de auditoria: mudança de estado, alteração de prioridade, atribuição, reatribuição, transferência de chamados por inativação de técnico, **reabertura**, alteração de política, envio de evidência, reprocessamento, cancelamento, validação automática, expurgo, alteração da preferência de notificação do gestor, cadastro e inativação de usuários |
| RN-122 | O registro de auditoria guarda quem realizou a operação, o quê, quando e sobre qual entidade |
| RN-123 | O registro de reabertura permite responder: quem reabriu, qual chamado, quando, com qual justificativa, e a transição ocorrida (`RESOLVIDO → ATRIBUIDO`) |
| RN-124 | O registro de auditoria é imutável durante seu período de retenção: não pode ser alterado nem removido por usuários ou por operações normais de negócio |
| RN-125 | A trilha de auditoria é interna: acessível apenas ao gestor |
| RN-126 | O isolamento por cliente é aplicado na camada de serviço, nunca por parâmetro vindo do cliente HTTP |

### 5.14. Identidade e usuários

| ID | Regra |
|---|---|
| RN-127 | A conta de cada usuário é criada no Keycloak pelo app-principal, via Admin API, com o papel correspondente ao perfil |
| RN-128 | A senha do usuário é definida diretamente no Keycloak; o sistema nunca recebe nem armazena senhas |
| RN-129 | O `sub` do Keycloak é o identificador único do usuário em todo o sistema: atribuição, auditoria e notificações |
| RN-130 | O Keycloak é a fonte oficial do papel do usuário, e a autorização usa sempre o papel presente no token. O app-principal mantém uma cópia do perfil, gravada somente por ele no cadastro, usada apenas em consultas — como listar os gestores a notificar — e nunca para autorizar. O vínculo de usuário do perfil cliente com a empresa cliente é mantido no app-principal |
| RN-131 | Papéis só são alterados pelo app-principal, via Admin API; alterações feitas diretamente no console do Keycloak não são suportadas |
| RN-132 | Inativar um usuário desabilita sua conta no Keycloak e marca o registro local como inativo; usuários nunca são excluídos |
| RN-133 | A inativação de um técnico com chamados em `ATRIBUIDO`, `EM_ATENDIMENTO` ou `RESOLVIDO` exige a indicação de um técnico substituto ativo; sem ele, a inativação é rejeitada |
| RN-134 | Na mesma transação da inativação, os chamados em `ATRIBUIDO` passam ao substituto; os em `EM_ATENDIMENTO` voltam para `ATRIBUIDO` com o substituto, com o atendimento em andamento encerrado como interrompido; os em `RESOLVIDO` mantêm o estado e passam a ter o substituto como técnico responsável |
| RN-135 | A transferência segue as regras da reatribuição: não reinicia nem recalcula prazos (RN-22), e o substituto recebe notificação interna de cada chamado transferido; o técnico inativado não recebe aviso de remoção |
| RN-136 | Se a gravação no banco falhar depois de uma alteração no Keycloak, a alteração é desfeita (compensação): na criação, a conta é removida; na inativação, a conta é reabilitada e nenhum chamado é transferido |
| RN-137 | A chamada à Admin API do Keycloak é protegida por timeout e retry; o app-principal a acessa com uma conta de serviço própria, cujas credenciais ficam em variáveis de ambiente |
| RN-138 | O e-mail de definição de senha é enviado pelo próprio Keycloak, pelo mesmo servidor SMTP do sistema |

---

## 6. Dados pessoais e política de retenção

### 6.1. Dados tratados

| Dado | Titular | Finalidade | Base legal | Retenção |
|---|---|---|---|---|
| Nome e e-mail corporativo | Usuários dos três perfis | Identificação, acesso e comunicação | Execução de contrato | Vigência do contrato mais prazo legal |
| Função ou cargo | Técnico e gestor | Atribuição de chamados e permissões | Execução de contrato | Idem |
| E-mail de contato do cliente | Pessoa de contato da empresa cliente | Envio das notificações por e-mail | Execução de contrato | Vigência do contrato mais prazo legal |
| Conteúdo das evidências | Cliente e seus equipamentos | Comprovação da execução do serviço | Execução de contrato | 180 dias após o encerramento ou cancelamento do chamado |
| Registros de auditoria | Usuários dos três perfis | Rastreabilidade e responsabilização | Legítimo interesse | 5 anos a partir do evento registrado |
| Notificações internas | Técnico e gestor | Comunicação operacional sobre chamados | Execução de contrato | 90 dias a partir da criação |
| E-mail do cliente e texto do posicionamento na mensagem de notificação | Pessoa de contato da empresa cliente | Envio do e-mail de notificação | Execução de contrato | Na tabela de saída do app-principal, até 7 dias após a publicação; não persistido pelo microsserviço; em caso de falha definitiva, até 7 dias na DLQ |

O sistema **não trata dados sensíveis** na acepção do art. 5º, II da LGPD. Duas decisões de minimização foram tomadas deliberadamente:

- **Não há coleta de geolocalização**, nem no envio nem nos metadados
- **Metadados EXIF são removidos na recepção**, antes de qualquer gravação, garantindo que coordenadas eventualmente embutidas pelo dispositivo nunca cheguem a ser armazenadas

O endereço e a razão social do cliente são dados da pessoa jurídica e não constituem dado pessoal; o e-mail de contato é tratado como dado pessoal quando identifica uma pessoa natural.

### 6.2. Justificativa do prazo de 180 dias para evidências

Os 180 dias são uma **decisão de negócio e arquitetura deste projeto**, não um prazo imposto pela LGPD. A legislação exige que exista uma política de retenção definida e coerente com a finalidade; não estabelece esse número.

O período foi escolhido para equilibrar cinco fatores:

- **Comprovação da execução do serviço** — a evidência precisa sobreviver ao encerramento do chamado para servir de prova
- **Possibilidade de contestação posterior** — o cliente pode questionar um atendimento semanas ou meses depois
- **Princípio da minimização** — manter dado pessoal além da necessidade da finalidade contraria a LGPD
- **Redução da retenção desnecessária de imagens** — o conteúdo perde utilidade prática após o encerramento definitivo
- **Redução do consumo de object storage** — mídia é o dado mais caro de armazenar no sistema

### 6.3. Justificativa do prazo de 5 anos para a auditoria

Também é uma **política de retenção definida pelo projeto**, não uma exigência legal específica.

A trilha de auditoria tem finalidade diferente da evidência. Ela não comprova a execução de um serviço, mas o comportamento do sistema e das pessoas dentro dele: rastreabilidade, responsabilização, investigação de incidentes, comprovação das operações realizadas, histórico de operações sensíveis e apoio à segurança e conformidade. Essa finalidade permanece útil por muito mais tempo do que a mídia do atendimento.

Além disso, registros de auditoria são linhas de texto estruturado, com volume ordens de grandeza menor que arquivos de imagem. Reter por cinco anos não produz impacto de armazenamento comparável ao das evidências.

### 6.4. Dados pessoais no microsserviço de notificações

O ms-notificacoes recebe pela fila apenas o necessário para compor a notificação: número e título do chamado, o identificador do usuário nas notificações internas e, somente nos e-mails, o e-mail de contato do cliente e, no e-mail de posicionamento, o texto do posicionamento. O e-mail e o texto do posicionamento são usados no envio e não são armazenados pelo microsserviço, que guarda apenas o identificador do evento, para garantir a idempotência. Na origem, esses dados ficam na tabela de saída do app-principal por até 7 dias após a publicação; quando um envio falha definitivamente, a mensagem fica na DLQ por no máximo 7 dias. As notificações internas armazenam o tipo do evento, o número e o título do chamado — o título é escrito pelo cliente e pode conter informação que ele próprio decidiu registrar — e nunca o conteúdo das evidências ou dados do contrato. Os 90 dias de retenção são decisão do projeto: uma notificação perde utilidade depois que o chamado é acompanhado.

### 6.5. Imutabilidade não é retenção permanente

São conceitos distintos, e o documento os separa de propósito:

- **Imutabilidade** significa que, enquanto existe, o registro não pode ser alterado nem apagado por usuário ou por operação de negócio
- **Retenção** define por quanto tempo o registro existe

O registro de auditoria é imutável **durante** seus 5 anos de retenção. Terminado o prazo, ele é removido — exclusivamente pela rotina automática, nunca por ação manual de qualquer perfil.

---

## 7. Limitações conhecidas e evolução futura

Limitações assumidas de forma consciente, não por omissão. Estão registradas aqui para servir de base aos ADRs e à pergunta "o que a equipe faria diferente hoje", cobrada na banca final.

### 7.1. Ausência de pausa no relógio de SLA

O sistema não possui a fase de "aguardando cliente", então nenhum relógio pode ser pausado. Isso significa que o prazo de resolução continua correndo mesmo quando a prestadora está bloqueada por causa externa: peça em falta, acesso negado ao local, cliente indisponível para agendamento.

Num sistema em produção isso geraria violações injustas, e a prestadora exigiria o mecanismo de pausa. A decisão foi tomada para manter o cálculo de tempo tratável dentro do prazo do projeto.

**Mitigação adotada:** os prazos de resolução (2 e 5 dias úteis) já embutem folga para absorver bloqueios curtos, em vez de serem apertados.

**Evolução futura:** introduzir a entidade de pausa, com estado `AGUARDANDO_CLIENTE`, motivo obrigatório, limite de duração acumulada e efeito exclusivo sobre o relógio de resolução — nunca sobre o de resposta, que mede uma obrigação puramente interna da prestadora.

### 7.2. Reabertura sem limite

Não há limite para o número de reaberturas de um mesmo chamado. Como cada reabertura reinicia os relógios de atendimento e resolução, um chamado reaberto repetidamente nunca acumula violação nesses dois relógios, ainda que o problema do cliente permaneça sem solução por meses.

**Evolução futura:** limitar o número de reaberturas por chamado, ou passar a apurar um indicador de reincidência separado dos três SLAs.

### 7.3. Processamento e download de imagens dentro do app-principal

Sem o microsserviço de evidências, um pico de envio de fotos compete por CPU com o atendimento da API, e essa parte não pode ser escalada isoladamente. O download também passa pelo app-principal, já que o MinIO não é exposto ao navegador: cada transferência ocupa a API pelo tempo do envio.

**Mitigação adotada:** processamento assíncrono com concorrência limitada por configuração; limite de 10 MB por imagem; visualização pela versão redimensionada, menor que a imagem recebida.

**Evolução futura:** se o volume de evidências crescer ou o vídeo voltar ao escopo, extrair o processamento de mídia para um serviço dedicado — o consumidor já é isolado por fila, o que reduz o custo dessa extração.

### 7.4. Outras evoluções previstas

| Item | Situação atual | Evolução |
|---|---|---|
| Aplicativo mobile do técnico | Web responsivo | App com fila local e envio ao recuperar conexão |
| Geolocalização da evidência | Não coletada; EXIF removido | Comprovação de presença em campo, com base legal e retenção definidas |
| Feriados | Não modelados | Calendário de feriados por contrato, afetando os três relógios |
| Múltiplas políticas por contrato | Uma política vigente | Política por tipo de serviço dentro do mesmo contrato |
| Alertas de prazo | Gestor e técnico são notificados quando o prazo já foi violado | Alerta preventivo de prazo próximo do vencimento |
| Atualização do sino | Consulta periódica (polling) | Envio em tempo real por WebSocket ou SSE |
| Vídeo como evidência | Fora do escopo | Vídeos curtos com transcodificação em serviço dedicado |
| Retenção configurável | Prazos fixos no sistema | Prazo de retenção parametrizável por contrato |
| Escala do app-principal | Instância única | Trava distribuída (ShedLock) para as rotinas e o publicador, permitindo várias instâncias |
| Download de evidências | Servido pelo app-principal | URL pré-assinada atrás de proxy reverso, tirando a transferência da API sem expor o MinIO diretamente |

---

## 8. Conformidade com o enunciado

Verificação item a item das exigências do documento da disciplina.

### 8.1. Requisitos funcionais mínimos (§4.2)

| Exigência | Onde é atendida | Situação |
|---|---|---|
| Mínimo 3 perfis com permissões distintas | RF-01 a RF-04, seção 2.1 | Atendido |
| Fluxo de negócio completo atravessando várias entidades | Seção 2.2, RF-12 a RF-37 | Atendido |
| Upload e download com object storage | RF-24, RF-28, RN-80 | Atendido |
| Relatório gerencial exportável | RF-38, RF-39 | Atendido |
| Trilha de auditoria | RF-40, RF-41, RN-121 a RN-125 | Atendido |
| Rotina agendada | RF-34, RF-35 | Atendido por duas rotinas de prazo de SLA (violação e validação automática); as rotinas de retenção e expurgo são de manutenção e ficam fora da contagem |

### 8.2. Requisitos não funcionais obrigatórios (§4.3)

| Exigência | Onde é atendida | Situação |
|---|---|---|
| Autenticação e autorização por IdP externo com papéis | RF-01, RF-02, RN-126 | Atendido |
| Integridade transacional | Seção 5.12, incluindo a tabela de saída (outbox) | Atendido |
| Auditoria de operações sensíveis | RN-121 a RN-124 | Atendido |
| Conformidade LGPD | Seção 6 | Atendido |
| Resiliência: timeout, retry, circuit breaker | RN-47, RN-49, RN-50 (SMTP); RN-137 (Admin API do Keycloak) | Atendido |
| Resiliência: DLQ e idempotência | RN-86 a RN-90 (evidências), RN-55, RN-56, RN-57 e RN-54 (notificações), RN-114 e RN-115 (outbox) | Atendido |
| Observabilidade | RF-63 a RF-65 | Parcial: painel Grafana é entrega de implantação |
| Escalabilidade comprovada por teste de carga | Seção 3.4 (premissas, com Toxiproxy); execução no roadmap | Cenário revisado para o ms-notificacoes |

### 8.3. Arquitetura exigida (§4.1)

| Elemento | Onde é atendida | Situação |
|---|---|---|
| Monólito modular Spring Boot com API REST documentada | Seção 3.1 | Definido |
| Microsserviço com escala independente | Seção 3.4 | Definido |
| Comunicação assíncrona por mensageria | Seções 3.2, 3.4 e 3.6; RN-46 a RN-57, RN-83 a RN-90, RN-113 a RN-116 | Definido |
| Frontend consumindo a API | Seção 3.1 | Definido |
| Todos os componentes em contêineres Docker | Seção 3 | Definido |

### 8.4. Decisões fechadas nesta revisão

| Tema | Decisão |
|---|---|
| Retenção das evidências | 180 dias após `ENCERRADO` ou `CANCELADO` |
| Exclusão manual de evidência | Não permitida |
| Expurgo de evidências | Automático |
| Estado `REABERTO` | Não existe |
| Reabertura | `RESOLVIDO → ATRIBUIDO` |
| Técnico após reabertura | Mantém o técnico atual |
| Novo atendimento | Criado a cada reabertura |
| SLA de resposta | Não reinicia |
| SLA de atendimento | Reinicia na data e hora da reabertura |
| SLA de resolução | Reinicia na data e hora da reabertura |
| Retenção da auditoria | 5 anos |
| Auditoria durante retenção | Imutável |
| Exclusão manual da auditoria | Não permitida |
| Expurgo da auditoria | Apenas por rotina automática |
| Microsserviço | `ms-notificacoes`, substituindo o `ms-evidencias` |
| Vídeo | Removido do escopo |
| Processamento de imagens | Assíncrono, no app-principal |
| Notificação ao cliente | E-mail |
| Notificação a técnico e gestor | Interna, pelo sino |
| Preferência do gestor | No chamado, individual por gestor |
| Retenção das notificações internas | 90 dias |
| Notificação de violação | Todos os gestores e o técnico atribuído, se houver |
| Notificação de reabertura | Técnico atribuído |
| Reatribuição | Em `ATRIBUIDO` e `EM_ATENDIMENTO`; técnico anterior recebe aviso |
| EXIF | Removido na recepção; hash calculado sobre a versão sanitizada |
| Formatos de imagem | JPEG, PNG e WebP |
| Falha do SMTP | Fila por canal; consumo pausado com circuito aberto; 5xx direto à DLQ; DLQ expira em 7 dias |
| Identidade | Conta no Keycloak criada pelo app-principal; `sub` como identificador; vínculo de negócio no app-principal |
| Cadastro do cliente | Razão social, e-mail de contato e endereço |
| Inativação de técnico | Exige substituto; chamados em `ATRIBUIDO`, `EM_ATENDIMENTO` e `RESOLVIDO` são transferidos na mesma transação |
| Ordem dos prazos (RN-30) | Vale para a configuração da política, não para a ordem dos eventos |
| Entrega de eventos | Outbox: gravação na mesma transação, publicação com confirmação do broker, entrega ao menos uma vez |
| `eventoId` | Presente em toda mensagem; determinístico na violação (chamado + relógio + marco inicial) |
| Conteúdo da mensagem | Número legível e título do chamado; texto do posicionamento no e-mail correspondente |
| Papel do usuário | Keycloak é a fonte oficial; cópia local só para consultas |
| Instância do app-principal | Única |
| Download de evidências | Servido pelo app-principal; MinIO restrito à rede interna do Docker |
| E-mail de definição de senha | Enviado pelo Keycloak |
