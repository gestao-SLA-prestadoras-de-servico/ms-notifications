# MS-NOT-10 — Evidências de segurança JWT/Keycloak

Data: 12/11/2026. Branch: `feature/ms-not-10-jtw-keycloak`, baseada no commit da MS-NOT-08.
Projeto Docker isolado: `ms-not-10-test`; Java 21, Spring Boot 4.1.1, Keycloak 26.4, PostgreSQL 17, RabbitMQ 4.1 e Mailpit 1.27.

## Execução

```powershell
# Integração de autenticação: senha temporária só no processo e no container descartável.
$env:IT_KEYCLOAK_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
try {
    docker compose -p ms-not-10-test -f compose.yaml -f infra/compose.keycloak-it.yaml run --build --rm keycloak-it
} finally {
    docker compose -p ms-not-10-test -f compose.yaml -f infra/compose.keycloak-it.yaml down
    Remove-Item Env:IT_KEYCLOAK_PASSWORD
}

# Regressão: portas distintas da stack principal.
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'
docker compose -p ms-not-10-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it
docker compose -p ms-not-10-test -f compose.yaml -f infra/compose.postgres-it.yaml run --no-deps --rm postgres-it
docker compose -p ms-not-10-test down
```

Os perfis são independentes: `keycloak-it` inclui somente `KeycloakJwtIT`; os demais excluem esse teste. Todos executam também a suíte local. Não houve instalação nova de dependências de produção.

## Resultados

| Verificação | Resultado |
|---|---|
| Suíte local | 137 testes, incluindo 38 casos HTTP/JWT/CORS e 1 do helper de identidade; sem falhas/erros/ignorados |
| Keycloak real | 6 integrações, sem falhas/erros/ignorados; `BUILD SUCCESS` |
| Regressão de mensageria/PostgreSQL | 52 integrações, sem falhas/erros/ignorados; `BUILD SUCCESS`; o Keycloak foi excluído desse perfil |
| Perfil somente PostgreSQL | 137 testes locais + 18 integrações PostgreSQL, sem falhas/erros/ignorados; `BUILD SUCCESS` |
| Imagem final por HTTP | JAR iniciado saudável; respostas 200/401/403/404 e preflight 200/403 conferidas com Keycloak real |
| Whitespace | `git diff --check` sem erros |

Logs locais ignorados pelo Git: `target/ms-not-10-keycloak-it-final.log`, `target/ms-not-10-mensageria-it.log`, `target/ms-not-10-postgres-it.log`, `target/ms-not-10-runtime-build.log` e `target/ms-not-10-runtime.log`.

Total de casos distintos aprovados: **195** (137 locais + 52 integrações de mensageria/PostgreSQL + 6 Keycloak). O perfil PostgreSQL repete um subconjunto desses casos e não foi somado novamente.

Ao final, todos os containers e a rede de `ms-not-10-test` foram encerrados. Os volumes PostgreSQL/RabbitMQ/definições e caches Maven foram preservados. Os quatro containers da stack principal estavam saudáveis na conferência final; nenhum comando desta validação os atualizou. Não houve commit, push ou alteração de branch.

## Casos de segurança

- Os testes locais usam uma chave RSA de 2048 bits gerada em memória, JWTs realmente assinados e um servidor HTTP JWKS. Não há mock do decoder nem autenticação injetada para dispensar assinatura.
- TECNICO e GESTOR recebem 200 no endpoint de consulta de teste e 204 no PATCH; CLIENTE, ADMIN, papéis com caixa/prefixo incorretos e papéis padrão recebem 403.
- `resource_access`, `scope`, `roles` e `perfil` não concedem acesso. Claims de papéis ausentes ou com estruturas inválidas resultam em 403, sem 500.
- JWT com assinatura de outra chave, algoritmo HMAC, token sem assinatura ou formato inválido recebe 401.
- Issuer incorreto, audiência incorreta/ausente, expiração vencida/ausente, nbf futuro e sub ausente/vazio/branco recebem 401. Há verificação do challenge Bearer.
- O sub retornado é o identificador opaco assinado; query `sub` e header `X-User-Id` não o substituem. O helper rejeita contexto ausente ou autenticação de sessão.
- Cookie, query `access_token`, Basic e sessão com um SecurityContext autenticado não substituem o Bearer. A API não cria cookie de sessão após autenticar.
- Health permanece público; rotas fora do escopo e outros actuators são negados. Despacho interno ERROR preserva o erro MVC, sem liberar `/error` para requisições HTTP normais.
- CORS aceita duas origens explícitas no cenário local, GET/PATCH e Authorization/Content-Type. Origem externa, origem com sufixo malicioso, origem `null`, DELETE e header não permitido são recusados. Preflight válido dispensa JWT e erros 401 mantêm o header CORS da origem válida.
- A aplicação de teste H2 e a regressão de consumidores iniciam com JWKS apontando para um IdP não disponível no localhost do runner: a inicialização não faz discovery nem depende de chaves remotas.

## Keycloak real e JAR final

`KeycloakJwtIT` usa realm/clients/usuários exclusivos de teste. Os tokens são emitidos pelo endpoint OIDC real e validados pelo decoder e filtros de produção. Foram conferidos os três perfis, o sub real, audiência ms-notificacoes, token do mesmo realm destinado apenas a app-principal, emissor incorreto e adulteração do sub sem refazer a assinatura.

O realm usa os scopes padrão `basic` e `roles`; a senha vem de variável gerada em memória, não de valor versionado. O grant de senha existe somente no client de automação. Nenhum usuário, papel, client ou arquivo do backend foi modificado.

A imagem final foi iniciada na mesma rede isolada, com issuer/JWKS do Keycloak de teste e origem CORS `http://localhost:4200`. Um cliente HTTP em container separado obteve tokens reais e chamou o servidor HTTP do JAR:

| Chamada | Observado |
|---|---|
| Health sem token | 200 |
| GET/PATCH notificações sem token e GET com token inválido | 401 |
| GET/PATCH com CLIENTE | 403 |
| GET/PATCH com TECNICO/GESTOR | 404: autenticação/autorização passou, endpoint de negócio ainda pertence à MS-NOT-11 |
| Rota fora do escopo com token válido | 403 |
| Preflight da origem local permitida | 200, com Access-Control-Allow-Origin correto |
| Preflight de origem externa | 403 |

Os endpoints que retornam sub/204 nos testes estão somente em `src/test`; o 404 do JAR confirma que não foi publicado controller de diagnóstico nem antecipada a API da MS-NOT-11.

## Achados durante a implementação

1. A primeira versão da configuração de teste foi descoberta pelo component scan e registrou o controller duas vezes, gerando `Ambiguous mapping`. Foi corrigida com `@TestConfiguration`/`@TestComponent` e import explícito.
2. O primeiro realm de teste incluía somente scope `roles` e emitia JWT sem sub. Os três casos de perfil foram corretamente recusados com `The sub claim is not valid`. A fixture passou a incluir o scope padrão `basic`, sem relaxar a validação da API.
3. A construção do despacho de erro de teste usou inicialmente um método ausente no builder do Spring 7; houve falha de compilação. Foi substituído por um request postprocessor compatível. O teste posterior de ERROR/404 passou.

## Limites

O contrato foi alinhado por leitura da configuração do backend, mas o Keycloak/backend ativos do usuário e o fluxo de login do frontend não foram alterados nem exercitados. A validação real usa outra instância Keycloak, com realm próprio. A atualização de mappers em um realm persistido continua sendo operação do ambiente.

A filtragem de dados por destinatário e a marcação de leitura são da MS-NOT-11. Esta entrega prepara a identidade e protege as rotas; não implementa consultas ao banco em endpoints novos. A MS-NOT-09 não consta da base desta branch e não foi integrada nesta task independente.

Referências: [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [CORS](https://docs.spring.io/spring-security/reference/7.0/servlet/integrations/cors.html) e [importação Keycloak com variáveis](https://www.keycloak.org/server/importExport).
