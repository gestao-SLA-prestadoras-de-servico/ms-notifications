# MS-NOT-04 — Criar o banco e as migrations Flyway

Requisitos: RN-55, RN-58 e RN-59. Depende das MS-NOT-01 e MS-NOT-03.

O PostgreSQL independente e as credenciais do serviço já são provisionados pelo
Compose da MS-NOT-03. Esta implementação adiciona o schema versionado, entidades
e repositórios para notificações internas e registros de processamento concluído.

| Critério | Solução |
|---|---|
| Banco lógico e credenciais próprios | Banco `ms_notificacoes` e `DB_*` do ambiente; nenhuma conexão com o banco do backend |
| Campos da notificação | `NotificacaoInterna` e tabela `notificacao_interna`: destinatário sub, evento, tipo, chamado, número, título e instantes de criação/leitura |
| Registro de sucesso | `EventoProcessado` e tabela `evento_processado`, com evento, canal e instante do processamento |
| Migrations em banco vazio | `V1__criar_notificacoes_e_eventos_processados.sql`, aplicada automaticamente pelo Flyway |
| Índices | Destinatário/criação/UUID em ordem decrescente, parcial por destinatário para não lidas, criação para expurgo; índices únicos para duplicações |
| Restrições | Not-null, campos textuais não vazios, tipos internos/canais conhecidos, leitura não anterior à criação e unicidade |

## Decisões

| Tema | Decisão e motivo |
|---|---|
| Integração Flyway | Starter do Spring Boot 4 e módulo PostgreSQL, com versões gerenciadas pelo parent existente |
| DDL | Flyway cria/atualiza, Hibernate somente valida; `clean` desabilitado e validação de checksums ativa |
| IDs locais | UUID gerado pelo JPA; não exige extensão PostgreSQL |
| IDs recebidos | Strings opacas preservadas, compatíveis com o contrato MS-NOT-02 e o evento determinístico de SLA |
| Tipos de notificação | Apenas os seis eventos internos; SQL rejeita tipos de e-mail nessa tabela |
| Tempo | `Instant` fornecido pelo chamador e `timestamp with time zone`; precisão PostgreSQL de microssegundos |
| Unicidade | Notificação por evento/destinatário; sucesso por evento/canal, abrangendo todos os destinatários do evento naquele canal |
| Retenção e relações | Sem FK/cascade entre marcadores e notificações; expurgo não deve apagar o registro de sucesso |
| Dados pessoais | Não há e-mail, texto de posicionamento ou payload nas entidades/tabelas |
| Suíte local | Teste de inicialização H2 usa DDL de teste, com Flyway desativado apenas naquele teste; não comprova migrations |
| Suíte PostgreSQL | Perfil explícito `postgres-it`, executado em PostgreSQL real; schema aleatório vazio por execução, sem limpar schemas existentes |

Os repositórios oferecem persistência, busca por evento/destinatário, paginação
por destinatário e contagem de não lidas, além de existência de sucesso por
evento/canal. O desempate por UUID torna a paginação estável para datas iguais.

A MS-NOT-05 implementará a coordenação concorrente e a gravação transacional do
sucesso após os efeitos. A MS-NOT-12 implementará o expurgo. Esta task não inicia
consumidores, não registra eventos ao recebê-los e não armazena dados de e-mail.

## Atualização e verificação

No banco vazio da MS-NOT-03, basta reconstruir e iniciar o serviço. Em bancos já
preenchidos, investigar conflitos antes de migrar; não habilitar baseline nem
apagar tabelas automaticamente. Migrations aplicadas não devem ser editadas.

O [README](../../README.md) contém comandos para startup e testes PostgreSQL,
tanto por Docker quanto por Maven no host.

Evidências: [relatório de testes](../testes/ms-not-04-banco-flyway.md).
