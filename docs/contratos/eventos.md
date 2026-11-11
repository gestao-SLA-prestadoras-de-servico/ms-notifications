# Contrato dos eventos de notificação

Implementação da MS-NOT-02, baseada nas RN-33 a RN-41, RN-46, RN-52 a RN-54,
RN-58 e RN-135 de [flowSLA.md](../flowSLA.md). O app-principal é o produtor;
o ms-notificacoes é o consumidor. O backend local ainda não possui DTO ou
publicador de notificações: este formato é a proposta de integração a ser
adotada pelo produtor, e não uma confirmação de compatibilidade já testada.

## Transporte e campos

Corpo JSON UTF-8 (`application/json`), publicado na exchange direta
`notificacoes`. A routing key é `email` ou `interna`, conforme a matriz.
Os bindings estão em [topologia.json](../topologia.json).

| Campo | Tipo JSON | Regra |
|---|---|---|
| `eventoId` | string | Obrigatório, não vazio; identificador opaco gerado pelo app-principal |
| `tipo` | string | Obrigatório; um dos nove valores exatos abaixo, sensível a maiúsculas |
| `chamado` | objeto | Obrigatório |
| `chamado.id` | string | Identificador interno obrigatório e não vazio; IDs numéricos devem ser serializados como string |
| `chamado.numero` | string | Número legível obrigatório e não vazio, por exemplo `#1042` |
| `titulo` | string | Título do chamado obrigatório e não vazio |
| `destinatarios` | array de strings | Obrigatório e não vazio; nenhum elemento nulo, vazio ou só com espaços |
| `textoPosicionamento` | string | Obrigatório e não vazio para `PRIMEIRO_POSICIONAMENTO`; ausente ou null nos demais |

Nos eventos internos, cada destinatário é o `sub` opaco do Keycloak (não o ID
local do técnico/gestor). Nos e-mails, cada elemento deve ser um endereço válido;
o produtor informa o e-mail de contato do cliente. A lista permite representar
destinatários múltiplos sem mudar o formato. O produtor seleciona os destinatários
e envia cada um uma única vez; o consumidor não consulta usuários, preferências,
contratos, banco nem API do app-principal para completar a mensagem.

## Matriz de eventos

| `tipo` | Canal / fila | Destinatários definidos pelo produtor |
|---|---|---|
| `ATRIBUICAO` | interna / `notificacoes.interna` | Técnico atribuído |
| `REATRIBUICAO` | interna / `notificacoes.interna` | Novo técnico ou substituto na transferência por inativação |
| `REMOCAO` | interna / `notificacoes.interna` | Técnico anterior na reatribuição comum; aviso não concede acesso ao chamado |
| `REABERTURA` | interna / `notificacoes.interna` | Técnico atualmente atribuído |
| `CONCLUSAO` | interna / `notificacoes.interna` | Gestores que ativaram a preferência naquele chamado |
| `VIOLACAO_SLA` | interna / `notificacoes.interna` | Todos os gestores e o técnico atribuído, se houver, independentemente da preferência |
| `PRIMEIRO_POSICIONAMENTO` | email / `notificacoes.email` | E-mail de contato do cliente; inclui o texto registrado pelo gestor |
| `RESOLUCAO` | email / `notificacoes.email` | E-mail de contato do cliente quando o chamado é marcado resolvido |
| `AVISO_ENCERRAMENTO` | email / `notificacoes.email` | E-mail de contato do cliente, um dia útil antes da validação automática |

A conclusão pode produzir dois eventos distintos: `CONCLUSAO` para o sino e
`RESOLUCAO` para e-mail, cada um com seu `eventoId`. Na reatribuição comum,
o produtor emite `REATRIBUICAO` para o novo técnico e `REMOCAO` para o anterior.
Na inativação (RN-135), emite somente `REATRIBUICAO` por chamado transferido,
sem aviso de remoção ao técnico inativado. Se não houver destinatários para
uma notificação, o produtor não publica uma lista vazia.

## Identidade, publicação e evolução

O app-principal gera o `eventoId` ao gravar o evento na outbox, na mesma
transação do negócio. Republicações preservam o ID e o conteúdo. Para violação
de SLA, o ID é determinístico a partir de **chamado + relógio + marco inicial
vigente**. Exemplo ilustrativo: `sla:1042:ATENDIMENTO:2026-11-11T11:00:00Z`.
O algoritmo/codificação exato pertence ao produtor; o consumidor trata o ID
como opaco e nunca gera ou recalcula esse valor. Cada relógio violado produz
seu próprio evento. Uma reabertura muda o marco dos relógios reiniciados,
permitindo identificar uma nova violação sem repetir o aviso anterior.

O corpo atual não exige campos de relógio ou datas de SLA: a identidade já vem
pronta e o aviso é de violação de SLA vinculada ao chamado. Conteúdo que exija
identificar o relógio deverá ser alinhado com o produtor antes de ampliar o contrato.

Campos desconhecidos, tipos desconhecidos, JSON malformado, valores com tipos
JSON incorretos e campos obrigatórios ausentes/null/em branco são inválidos.
Mudanças de nomes, tipos ou campos exigem alinhamento entre produtor e consumidor;
não há conversão silenciosa de versões neste contrato.

## Validação e exemplos executáveis

[`exemplos/validos`](exemplos/validos) contém um payload completo por tipo.
`reatribuicao.json` representa a transferência ao substituto: o destinatário é
somente `sub-tecnico-substituto`, sem evento de remoção ao inativado.
[`exemplos/invalidos`](exemplos/invalidos) contém casos de campos ausentes,
tipo desconhecido, destinatários incorretos e posicionamento sem texto.

`LeitorEventoNotificacao.ler(payload)` desserializa e aplica Bean Validation,
incluindo e-mail conforme o canal. A sobrecarga `ler(payload, canal)` verifica
também o canal da fila. Falhas lançam `EventoInvalidoException`, sem payload,
valor rejeitado ou causa contendo dados pessoais. Os consumidores futuros devem
usar esse ponto de entrada antes de qualquer efeito e classificar a exceção como
falha definitiva; o encaminhamento para DLQ pertence à MS-NOT-08.
O DTO sozinho não executa validação automaticamente; a validação adicional do
grupo de e-mail é responsabilidade do leitor.

E-mail de contato e texto do posicionamento só trafegam na mensagem: não devem
ser persistidos ou registrados em logs. A sobrescrita de `toString` do evento
omite esses dados. Os exemplos são fictícios.

Execute `./mvnw test` (Windows: `mvnw.cmd test`). A suíte lê os mesmos JSONs
versionados da documentação, cobre todos os tipos/canais e verifica campos
obrigatórios, validação condicional e rejeição de mensagens inválidas.
