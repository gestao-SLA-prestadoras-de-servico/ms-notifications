# MS-NOT-11 — Criar a API de consulta e marcação de leitura

Requisitos RF-59/RF-60 e RN-42 a RN-45; depende das MS-NOT-04 e MS-NOT-10.

| Critério | Implementação |
|---|---|
| Consultar somente notificações próprias | Service obtém `IdentidadeAutenticada.sub()` e aplica o filtro em todas as consultas |
| Página das mais recentes e total não lidas | DTO explícito com itens/totais; ordem `criadoEm DESC, id DESC`; contagem global do sub |
| PATCH retorna 204 e preserva primeira leitura | UPDATE atômico por ID/sub com COALESCE; sucesso inclusive quando já lida |
| ID inexistente/de outro usuário retorna 404 | Zero linhas correspondentes produz a mesma resposta Problem Details |
| Identificador do chamado e OpenAPI | DTO inclui chamadoId/número/título/mensagem; contrato OpenAPI 3.1 em `docs/openapi.json` |

## Decisões

- Reutilizar entidade, migration, índices, repository, `Clock` e identidade JWT existentes. Nenhuma migration ou dependência nova.
- Separar controller, service transacional e DTOs; não serializar entidades JPA nem o formato interno de `Page` do Spring Data. O contrato inclui `itens`, `pagina`, `tamanho`, `totalElementos`, `totalPaginas` e `totalNaoLidas`.
- Página padrão 0, tamanho padrão 20 e máximo 100. Entrada inválida recebe 400. Limitar deslocamento ao inteiro suportado pelo JPA evita overflow/500 para páginas extremas.
- Ordem fixa com desempate por ID; não usar Pageable recebido diretamente da requisição, que permitiria ordenação arbitrária. Parâmetros extras de identidade não participam da consulta.
- Ler página e contagens em transação REPEATABLE READ somente leitura para manter um snapshot consistente por resposta. Não há garantia de snapshot compartilhado entre requisições diferentes.
- Atualizar leitura com uma instrução SQL gerada por JPQL, sem buscar a entidade antes de conferir o dono. O banco serializa concorrentes na linha; COALESCE é avaliado sobre a versão atual e conserva a primeira leitura. O resultado é o número de linhas correspondentes, inclusive se a data já existia.
- A data usa o Clock do serviço e nunca fica antes de criadoEm, preservando a restrição da V1 mesmo com relógio atrasado. Repetições podem escrever a mesma linha novamente, sem mudar a data.
- Zero linhas atualizadas significa recurso não encontrado para o sub autenticado, sem distinguir inexistência de pertencimento a terceiro. O erro não expõe destinatário, dados persistidos ou exceções internas.
- Aplicar segurança por rota da MS-NOT-10 e `@PreAuthorize` no controller. O helper central de identidade é usado dentro do service; nem gestor pode escolher outro destinatário.
- Respostas 400/404 usam Problem Details sanitizado, restrito ao controller de notificações. 401/403 continuam nos filtros Bearer existentes.
- OpenAPI estático versionado, sem introduzir biblioteca/UI ou liberar rota adicional. O build inclui o documento para o teste de correspondência com o JSON público.
- RN-45 continua no app-principal ao navegar: a notificação não concede acesso ao chamado. O frontend trata a recusa atual do backend com aviso, sem renderizar dados do chamado. Não há chamada síncrona entre serviços para compor notificações.

## Atualização e verificação

API, exemplos, paginação, erros e comando de uso estão no [README](../../README.md#consulta-e-leitura--ms-not-11). Atualize a aplicação com `docker compose up -d --build --no-deps ms-notificacoes`; não é necessário recriar banco ou RabbitMQ.

O backend é apenas referência de integração e não foi modificado. A branch atual foi preservada, sem commit/push. Esta task não integra a MS-NOT-09 ausente da base atual.

Testes e limites: [relatório](../testes/ms-not-11-api-notificacoes.md).
