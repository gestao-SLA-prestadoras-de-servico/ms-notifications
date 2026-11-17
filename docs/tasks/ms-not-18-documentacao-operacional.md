# MS-NOT-18 — Documentação operacional

O [manual de operação](../operacao.md) reúne inicialização, integração com produtor, frontend e Keycloak, diagnóstico, inspeção de filas, reprocessamento de DLQ e decisões sobre idempotência, recuperação SMTP e retenção. Ele referencia os contratos e os resultados de resiliência e escala. O README mantém as variáveis e comandos detalhados.

A integração com a outbox do backend continua dependente de implementação e validação conjunta; o manual identifica esse limite. O procedimento de DLQ não é atômico e pode gerar repetição, conforme a garantia documentada do serviço.
