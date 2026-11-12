# MS-NOT-10 — Proteger a API com JWT do Keycloak

Requisitos RF-01/RF-02, RN-43/RN-44 e RN-129/RN-130. Dependências: MS-NOT-01 e configuração Keycloak.

## Critérios e solução

| Critério | Implementação |
|---|---|
| Assinatura, emissor e validade | `NimbusJwtDecoder` RS256, JWKS, validador padrão com issuer e validações de audience, exp obrigatória e sub não vazio |
| Somente TECNICO/GESTOR | `SecurityConfig` exige esses papéis nas rotas `/notificacoes` e `/notificacoes/**`, exclusivamente de `realm_access.roles` |
| Identidade pelo sub | `IdentidadeAutenticada.sub()` centraliza a leitura da autenticação JWT, preservando o identificador opaco |
| 401/403 | Resource Server e handlers Bearer padrão; ausência/invalidade retorna 401, falta de papel retorna 403 |
| Frontend e CORS | Origens explícitas por ambiente, GET/PATCH, Authorization/Content-Type, sem autenticação por cookie |

## Decisões

- Reutilizar o starter OAuth2 Resource Server já presente, sem biblioteca adicional de produção.
- Chaves são verificadas via JWKS e cache do Nimbus/Spring Security. O issuer público é validado independentemente da URL interna usada para buscar chaves. Não há discovery no startup nem dependência síncrona do IdP para consumir eventos.
- Aceitar somente RS256, algoritmo padrão do realm consultado. `JwtValidators.createDefaultWithIssuer` preserva a validação de exp/nbf e tolerância temporal padrão de 60 segundos. Exp precisa existir, e sub não pode ser ausente ou branco.
- Exigir audiência `ms-notificacoes`, já provisionada nos mappers dos clients do backend consultado. Um token apenas para `app-principal` não autoriza este serviço.
- Mapear apenas os dois papéis permitidos do realm. Papéis padrão, client roles, scopes e claims arbitrários são ignorados. Estruturas de papéis ausentes/malformadas não geram 500 nem privilégios.
- Não consultar registro local de usuário ou API do backend para autenticar: o serviço possui apenas dados de notificações. O token assinado é a fonte de papel e identidade, conforme RN-130.
- Desabilitar sessão, request cache e CSRF na API exclusivamente Bearer. Não habilitar login de formulário, Basic ou resolução de token em cookie/query string. CORS é aplicado antes da autenticação.
- Manter somente health público e negar outras rotas por padrão. Habilitar segurança por método para os futuros endpoints, além da restrição por rota.
- Permitir apenas o despacho interno `ERROR` do servlet, preservando 404/erros do MVC sem liberar requisições HTTP externas para `/error`.
- Não criar endpoints de consulta/leitura antecipadamente. A MS-NOT-11 deve usar o helper de identidade para filtrar todas as operações; parâmetros de destinatário não participam da identidade autenticada.
- Usar endpoints exclusivos dos testes para provar 200/204 sem publicar uma API de diagnóstico na aplicação. Esses componentes são excluídos do component scan normal pelo suporte de teste do Spring Boot.
- Usar origem local `http://localhost:4200`, alinhada ao `FRONTEND_URL` dos exemplos do backend; a lista continua configurável por ambiente.
- Criar profile/Compose Keycloak de teste separado, sem portas publicadas e com senha efêmera. O grant de senha é habilitado somente no client de automação isolado, sem mudar o fluxo Authorization Code/PKCE do frontend.

## Configuração e atualização

As quatro variáveis de runtime estão em `.env.example`, `application.yml` e `compose.yaml`. O [README](../../README.md#jwt-do-keycloak--ms-not-10) descreve issuer/JWKS dentro e fora do Docker, audiência e origens.

O backend foi consultado somente para alinhar o contrato: realm `flowsla`, papéis `TECNICO`/`GESTOR` e mapper de audience `ms-notificacoes`. Nenhum arquivo ou realm existente foi alterado. Caso um realm persistido seja anterior aos mappers, sua atualização fica a cargo da operação do Keycloak; não se reduz a validação de audiência para contornar esse estado.

Não há migrations, alterações na topologia RabbitMQ ou mudanças no contrato de eventos. Esta branch parte da MS-NOT-08; a implementação de JWT não depende do circuit breaker da MS-NOT-09 e não altera essa parte do processamento.

Verificação: [relatório de testes](../testes/ms-not-10-jwt-keycloak.md).
