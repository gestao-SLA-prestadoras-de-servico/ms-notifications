# MS-NOT-03 — Configurar Docker e a topologia do RabbitMQ

Requisitos: RF-51, RN-48, RN-56 e RN-57. Depende das MS-NOT-01 e MS-NOT-02.

O ambiente deste repositório passa a iniciar o microsserviço, PostgreSQL,
RabbitMQ e Mailpit sem depender da infraestrutura do backend. O serviço
`rabbitmq-init` gera definições e credenciais de desenvolvimento em volume.

| Critério | Implementação |
|---|---|
| Ambiente reproduzível com quatro serviços | `Dockerfile`, `compose.yaml`, `.env.example` e health checks |
| Exchange encaminha aos dois canais | `docs/topologia.json`: exchange direta e bindings `email`/`interna` |
| Filas e mensagens sobrevivem a restart | Exchanges/filas duráveis, volume de dados e nodename estável; smoke publica com `delivery_mode=2` e verifica restart/recriação |
| DLQ por canal, retenção de sete dias | `notificacoes.dlx`, duas DLQs e `x-message-ttl=604800000` |
| Várias instâncias | Porta HTTP dinâmica em loopback, sem `container_name`; quatro réplicas verificadas |

## Decisões

| Tema | Decisão e motivo |
|---|---|
| Imagens | Maven 3.9.11/Temurin 21, JRE Temurin 21 Alpine, PostgreSQL 17 Alpine, RabbitMQ 4.1 management Alpine, Mailpit 1.27 e Python 3.13 Alpine. PostgreSQL/RabbitMQ/Mailpit mantêm as linhas já usadas pelo backend; tags permitem receber patches |
| Topologia | Arquivo único em `docs/topologia.json`, importado no boot; foram removidos os recursos de evidências, cuja responsabilidade é do app-principal |
| Credenciais | Variáveis obrigatórias no Compose; init gera hash SHA-256 salted em volume. Não há senhas no JSON versionado ou no contexto do build |
| Vhost | Configurável por `RABBITMQ_VHOST`, aplicado a todas as definições e permissões |
| Isolamento | Rede e volumes com escopo do projeto Compose; nenhuma dependência de arquivos ou containers do backend |
| DLQ | Argumentos de fila, incluindo TTL de sete dias, mantidos na fonte única da topologia |
| Persistência | Filas classic em nó único, volume e identidade estável. O produtor deve usar persistência, confirms e tratar ausência de rota; a infraestrutura não transforma mensagens transitórias em persistentes |
| Escala | Porta HTTP do host atribuída pelo Docker; consulta com `docker compose port --index N ms-notificacoes 8081` |
| Mailpit | Caixa de testes efêmera; o banco e o broker têm volumes persistentes |

O build executa a suíte Java antes de gerar a imagem final, que usa usuário
sem privilégios. O README contém comandos de execução, portas, escala,
persistência, atualização e validação.

Não foram implementados consumidores, retries ou integração com o produtor.
Esses itens pertencem às tasks seguintes. Nenhum arquivo do backend foi alterado.

## Atualização e verificação

Use uma stack nova para validar a topologia inicial. Mudanças em argumentos
imutáveis de filas existentes precisam de migração com preservação das mensagens;
não exclua filas para aplicar configurações. Senhas do PostgreSQL existentes
precisam ser alteradas no banco, além do `.env`.

`docker compose down` preserva dados; não use `down -v` para uma atualização.

Procedimento reproduzível: seção Docker do [README](../../README.md).
Evidências: [relatório de testes](../testes/ms-not-03-docker-rabbitmq.md).
