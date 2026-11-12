# MS-NOT-11 — Evidências da API de consulta e leitura

Data: 12/11/2026. Branch preservada: `feature/ms-not-10-jtw-keycloak`, sobre a MS-NOT-10.
Stack isolada: `ms-not-11-test`; Java 21, PostgreSQL 17, RabbitMQ 4.1, Mailpit 1.27 e Keycloak 26.4.

## Execução reproduzível

```powershell
$env:POSTGRES_LOCAL_PORT = '25432'
$env:RABBITMQ_LOCAL_PORT = '25672'
$env:RABBITMQ_MANAGEMENT_LOCAL_PORT = '25673'
$env:MAIL_SMTP_LOCAL_PORT = '21025'
$env:MAIL_UI_LOCAL_PORT = '28025'

docker compose -p ms-not-11-test -f compose.yaml -f infra/compose.postgres-it.yaml run --build --rm postgres-it
docker compose -p ms-not-11-test -f compose.yaml -f infra/compose.postgres-it.yaml -f infra/compose.mensageria-it.yaml run --build --rm postgres-it

$env:IT_KEYCLOAK_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
try {
    docker compose -p ms-not-11-test -f compose.yaml -f infra/compose.keycloak-it.yaml run --build --rm keycloak-it
} finally {
    docker compose -p ms-not-11-test -f compose.yaml -f infra/compose.keycloak-it.yaml --profile test down
    Remove-Item Env:IT_KEYCLOAK_PASSWORD
}
```

Nesta execução, os runners finais usaram `-o -B -P<perfil> verify` depois de preparar os caches Maven. Sem cache prévio, use os comandos acima para baixar dependências normalmente.

## Resultados

| Verificação | Resultado |
|---|---|
| Testes locais | 137 testes, sem falhas/erros/ignorados |
| PostgreSQL | 37 integrações: 19 novas da API e 18 existentes; sem falhas/erros/ignorados; `BUILD SUCCESS` |
| Mensageria completa | 71 integrações (19 novas + 52 existentes), sem falhas/erros/ignorados; `BUILD SUCCESS` |
| Keycloak real | 6 integrações de segurança, sem falhas/erros/ignorados; `BUILD SUCCESS` |
| Fluxo operacional | RabbitMQ → PostgreSQL → API da imagem final, com JWT emitido pelo Keycloak real; passou |
| OpenAPI | JSON 3.1 parseado, referências internas resolvidas e campos públicos comparados com respostas reais da API |
| Whitespace | `git diff --check` sem erros |

Logs ignorados pelo Git: `target/ms-not-11-postgres-it-final.log`, `target/ms-not-11-mensageria-it-final.log`, `target/ms-not-11-keycloak-it.log`, `target/ms-not-11-runtime-build.log` e `target/ms-not-11-runtime.log`.

Total aprovado: **214 casos distintos** (137 locais + 71 de mensageria/PostgreSQL + 6 Keycloak). A execução do perfil PostgreSQL repete um subconjunto desses casos e não é somada novamente.

A stack isolada foi encerrada ao final, sem remover volumes. Permaneceram os volumes de PostgreSQL, RabbitMQ, definições e caches Maven. Os quatro containers da stack principal estavam saudáveis na conferência final e não foram atualizados por esta validação. Não houve commit, push ou mudança de branch.

## Cobertura dos 19 testes novos

- Consulta de página própria, outra página e contagem global de não lidas, inclusive quando há notificações fora da página.
- Parâmetros extras `sub`/`destinatarioSub` e `sort` não substituem identidade nem ordenação.
- Ordenação da mais recente para a mais antiga e desempate por UUID decrescente, com páginas estáticas sem repetição.
- Usuário sem notificações, página além do fim, padrões 0/20 e tamanho máximo 100.
- Gestor também limitado ao seu próprio sub, sem acesso especial às notificações de terceiros.
- Sete casos de paginação inválida: negativos, zero, acima do máximo, textos, inteiro fora do limite e overflow de deslocamento; todos retornam 400.
- PATCH retorna 204 vazio, preserva o primeiro instante ao repetir e reduz totalNaoLidas sem alterar totalElementos.
- UUID inexistente e pertencente a outro usuário retornam 404 com o mesmo título/detalhe, mantendo os dados do outro intactos.
- UUID malformado retorna 400; ausência de autenticação e perfil CLIENTE recebem 401/403 antes da validação dos parâmetros.
- Respostas incluem chamadoId/número/título/mensagem e coincidem com os campos públicos do OpenAPI; não expõem destinatarioSub/eventoId. lidaEm inicial é null.
- Relógio anterior à criação não viola a restrição de data: a leitura fica na data de criação.
- Concorrência com duas transações reais: a primeira atualiza e mantém o lock aberto; a segunda usa outra conexão e bloqueia. O teste observa `pg_stat_activity.wait_event_type='Lock'`, libera a primeira, aguarda ambas terminarem e confere a primeira data preservada.

`NotificacoesApiPostgresIT` usa controllers, filtros, autorização por método, service, repository, Flyway e PostgreSQL reais em schema `it_<uuid>` exclusivo. Somente decoder e Clock são substituídos para controlar identidade/tempo nesses casos de dados. A assinatura JWT não é simulada nos testes de segurança existentes nem no fluxo operacional.

## Fluxo com a imagem final

Foi iniciada a imagem da aplicação com issuer/JWKS do Keycloak de teste, sem recriar banco/broker. Um cliente em outro container obteve tokens reais para técnico, gestor e cliente. Usando seus subs reais, publicou três eventos internos persistentes no RabbitMQ: dois para o técnico e um para o gestor.

Após o consumo assíncrono:

1. GET do técnico retornou uma página de tamanho 1, dois elementos totais e duas não lidas, com chamadoId e número corretos.
2. Mesmo enviando o sub do gestor na query, a listagem do técnico continuou contendo somente suas duas notificações.
3. PATCH próprio retornou 204; a contagem mudou de 2 para 1. A repetição retornou 204 e preservou exatamente o primeiro lidaEm.
4. PATCH de notificação do gestor e de UUID inexistente retornaram 404 com o mesmo detalhe; a notificação do gestor permaneceu não lida.
5. Paginação inválida retornou 400, chamada sem token retornou 401, cliente recebeu 403 e health permaneceu 200.

Saída observada:

```text
OK: RabbitMQ -> PostgreSQL -> API com JWT real; pagina e contador 2/2, outro usuario isolado, PATCH 204 repetido preserva data, contador 2->1, erros 400/401/403/404 corretos
```

## Achados e correções da validação

- A primeira preparação do runner ficou sem progresso em um download Maven. Somente esse runner foi interrompido (exit 143). O cache de teste anterior foi montado somente para leitura e copiado para o volume exclusivo da MS-NOT-11; os testes seguintes rodaram offline. Nenhum dado de PostgreSQL/RabbitMQ foi removido.
- A primeira rodada PostgreSQL teve uma falha no teste concorrente: o token criado manualmente pelo worker não estava autenticado, então a primeira atualização não era alcançada. O contexto do worker foi corrigido e a exceção de Future passou a ser exposta em caso de falha. Depois disso, todos os 37 testes PostgreSQL passaram, incluindo lock real e preservação de data.
- A primeira regressão completa detectou uma asserção antiga da MS-NOT-08 que consultava a fila imediatamente após NACK/cancelamento: esperava 1 e observou 0 durante a devolução assíncrona. O teste agora aguarda a mensagem na origem e verifica a DLQ vazia. Nenhuma regra de retry de produção foi alterada. Na repetição, todos os 71 testes de integração passaram.

## Limites

O fluxo operacional prova o microsserviço desde a publicação de eventos no broker até a leitura com JWT real. Não valida a outbox do backend, o login no frontend ou a autorização atual do chamado. Nenhum arquivo, banco ou realm do backend foi modificado.

A autorização do chamado continua no app-principal ao navegar; o frontend trata a recusa com aviso sem dados do chamado. A notificação não concede acesso ao recurso. O snapshot da listagem vale por resposta, podendo haver mudanças entre chamadas de polling/paginação.

O OpenAPI é um documento estático versionado; não foi adicionada UI/rota de documentação. A revisão estrutural e o contrato JSON foram verificados, sem alegar execução de um validador externo completo da especificação.
