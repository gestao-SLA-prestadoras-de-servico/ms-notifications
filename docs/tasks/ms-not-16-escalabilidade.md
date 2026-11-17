# MS-NOT-16 — Escalabilidade independente

Requisitos: seção 3.4 e arquitetura da seção 8.3; RN-55 e RN-58. Depende das MS-NOT-13 e MS-NOT-15.

O perfil `escala-it` executa o JAR empacotado com uma e quatro instâncias, usando Testcontainers. PostgreSQL, RabbitMQ, Mailpit e Toxiproxy são exclusivos de cada rodada. Somente o microsserviço é multiplicado; não há dependência de backend ou Keycloak para processar os eventos.

| Critério | Implementação |
|---|---|
| Mesma carga com uma e quatro instâncias | Gerador determinístico; mesmos bytes e eventoIds em bancos novos; SHA-256 registrado |
| Latência SMTP controlada | Toxiproxy 2.13.0 entre cada instância e Mailpit; toxic downstream, 100 ms, jitter zero e toxicity 1 |
| Condições comparáveis | 1 CPU, 768 MiB, heap máximo 384 MiB, concorrência 1 por canal, prefetch 1 e pool JDBC máximo 4 por instância |
| Drenagem, vazão e erros | Amostras de ACKs, commits, falhas e CPU por instância; cálculo de e-mails únicos por segundo |
| Verificação de duplicações | Oito republicações na carga padrão; marcadores e ACKs de duplicata conferidos; 32 envelopes SMTP com destinatários distintos e completos |
| Isolamento do canal interno | Um evento interno é publicado junto à carga SMTP; persistência e tempo observado são registrados |
| Gráficos e conclusões | Script Matplotlib gera comparação de duração/vazão e curvas de drenagem, sem exigir um ganho mínimo |

## Metodologia

Por padrão, duas repetições são executadas em ordem 1–4–4–1. Cada rodada parte de infraestrutura nova e usa oito eventos de aquecimento no total; a suíte verifica que todas as instâncias processaram pelo menos um. O aquecimento por JVM pode diferir (oito eventos para a JVM única, cerca de dois por JVM no cenário de quatro), portanto este é um experimento funcional de escala com carga pequena, não um benchmark de regime permanente/JIT.

Os consumidores são pausados após o aquecimento. Publicam-se 32 eventos únicos, com repetição imediata de cada quarto evento, totalizando 40 entregas persistentes confirmadas. O broker precisa reportar todas as entregas pendentes antes da medição. O início precede a liberação dos processos; o fim é observado quando todos os commits e ACKs do lote aparecem. A duração inclui o custo de liberar os containers e a resolução da coleta (250 ms mais HTTP/JDBC), excluindo boot, aquecimento e publicação. A inspeção final do Mailpit e das estatísticas de filas não entra no tempo.

A vazão útil divide os eventos únicos pela duração. Entregas duplicadas são trabalho adicional de idempotência e não são contadas como novos e-mails. A checagem SMTP verifica o conjunto completo de destinatários, além da quantidade, para impedir que uma duplicação esconda uma mensagem ausente.

O toxic atrasa os dados no sentido servidor → cliente; não representa um atraso único de 100 ms por e-mail nem uma latência simétrica. A conversa SMTP pode ter várias respostas atrasadas. A configuração efetiva é salva em cada rodada. Referência: [API e semântica dos toxics no Toxiproxy](https://github.com/Shopify/toxiproxy#toxics).

Os limites de infraestrutura são mantidos: PostgreSQL 2 CPUs/768 MiB, RabbitMQ 1 CPU/768 MiB, Mailpit 1 CPU/256 MiB e Toxiproxy 0,5 CPU/128 MiB. Os limites são tetos, não reservas. As quatro instâncias recebem quatro vezes o teto agregado de recursos da instância única e compartilham o host; não é uma comparação com orçamento total de CPU constante. Imagens, limites efetivos, ambiente Docker e hash do JAR são registrados.

O teste falha por perda, duplicação, falhas de processamento, filas residuais ou limite de drenagem excedido. Um ganho pequeno ou negativo é registrado como resultado válido. Não se presume escalabilidade linear nem se extrapolam os resultados para produção. A garantia de idempotência SMTP permanece sujeita à janela entre o envio externo e o commit, discutida nas tasks anteriores; este ensaio não injeta quedas nessa janela.

Configuração, reprodução e resultados: [testes](../testes/ms-not-16-escalabilidade.md).
