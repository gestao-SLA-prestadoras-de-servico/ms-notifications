# MS-NOT-13 — Observabilidade do serviço e das filas

| Critério | Implementação |
|---|---|
| Saúde e métricas | Actuator expõe health, info e Prometheus; liveness é independente da disponibilidade SMTP |
| Processamento e falhas | Contadores por canal e resultado ou categoria, sem identificadores pessoais nos rótulos |
| Circuit breaker SMTP | Estado real, disponibilidade da instrumentação e transições do circuito |
| Filas e DLQs | Prometheus coleta métricas por fila do RabbitMQ; dashboard do Grafana apresenta profundidade, consumidores, entregas e DLQs |
| Correlação | Logs usam hash SHA-256 do identificador validado do evento, sem payload ou destinatário |
| Falha SMTP | Circuito pausa o consumidor de e-mail e preserva a entrega; o canal interno e a liveness continuam disponíveis |

`notificacoes.eventos.processados` conta eventos confirmados no banco, antes do ACK; duplicatas não incrementam esse contador. `notificacoes.entregas` registra resultados por canal após o ACK, enquanto `notificacoes.falhas` registra ocorrências de falha por categoria, podendo haver mais de uma ocorrência por entrega. O hash permite correlação e é pseudônimo, não anônimo.

A MS-NOT-09 foi integrada à árvore de trabalho como dependência, incluindo recuperação SMTP. Os agendadores de recuperação e retenção são independentes. Quando o circuito está desabilitado, a métrica de habilitação vale zero e não é informado um estado CLOSED fictício.

O overlay local habilita os plugins de métricas e gerenciamento do RabbitMQ e deve recriar o broker para aplicar a configuração. Os volumes são preservados. A coleta por objeto é apropriada para esta topologia; avalie sua cardinalidade em brokers com muitas filas. O endpoint Prometheus não exige JWT: em produção, restrinja o acesso pela rede ou por um proxy. A senha inicial do Grafana é persistida em seu volume.

Configuração e operação: [README](../../README.md). Evidências: [testes](../testes/ms-not-13-observabilidade.md).
