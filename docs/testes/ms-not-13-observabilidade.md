# Testes — MS-NOT-13

## Regressão

A suíte Maven no runner Docker isolado concluiu com sucesso: 161 testes locais e 76 testes de integração com PostgreSQL, RabbitMQ e Mailpit. Ela cobre a abertura e o fechamento do circuito real, as métricas de estado e a execução independente dos agendadores de retenção e SMTP. O runner de testes usa um mínimo de uma conexão ociosa por pool para evitar esgotar as conexões do PostgreSQL durante o cache de contextos Spring; a configuração da aplicação não foi reduzida.

Uma tentativa anterior de regressão coincidiu com a interrupção intencional do Mailpit no smoke test e causou timeout no teste de e-mail. A suíte foi repetida em sequência, sem a interrupção concorrente, e passou.

## Prova operacional

O script `infra/smoke_observabilidade.py` passou com o projeto Docker isolado `ms-not-13-test`. Ele verifica o provisionamento do dashboard, coleta Prometheus do serviço e do RabbitMQ, contadores por canal, DLQ e entregas com ACK manual. Em seguida interrompe o Mailpit isolado e confere a abertura do circuito, a falha observável, a preservação da fila de e-mail, o processamento interno e a liveness; ao restaurar o Mailpit, verifica o fechamento do circuito, a drenagem da fila e o envio. Também verifica que o container da aplicação não reiniciou e que o log contém o hash do evento sem o destinatário.

Para reproduzir, configure as portas isoladas e `GRAFANA_ADMIN_PASSWORD`, inicie o projeto com `compose.yaml` e `infra/compose.observabilidade.yaml` e execute `python infra/smoke_observabilidade.py`. Execute a suíte de integração e o smoke em sequência, pois o smoke interrompe temporariamente o Mailpit. O script publica dados sintéticos e pode deixar mensagens nas DLQs e registros no banco de teste; não limpa volumes. A stack pode ser parada com `docker compose -p ms-not-13-test -f compose.yaml -f infra/compose.observabilidade.yaml down`, sem `-v`.
