# MS-NOT-09 — Implementar circuit breaker e recuperação do SMTP

Requisitos RN-47 a RN-49; depende da MS-NOT-08.

| Critério | Implementação |
|---|---|
| Abrir diante de indisponibilidade SMTP | Resilience4j core 2.4.0 protege somente a chamada SMTP; configuração por ambiente |
| Pausar consumidor de e-mail | Transições disparam controle assíncrono do container `notificacoesEmail`; `forceStop` devolve prefetch |
| Preservar tentativas enquanto aberto | Exceção própria de controle recebe NACK/requeue da entrega original, sem publicação em retry/DLQ |
| Sondagem com consumidor pausado | Scheduler dedicado chama `testConnection()` após a espera de OPEN |
| Recuperação automática | HALF_OPEN reservado à sonda; conexão válida fecha o circuito e reinicia o container pausado |
| Isolamento do canal interno | Nenhuma parada do registry inteiro ou do listener interno; sem dependência nova de banco/topologia |

## Decisões

- Núcleo Resilience4j integrado por código, sem depender de um starter destinado a outra versão do Spring Boot.
- Padrões conservadores: janela de uma chamada e abertura na primeira falha de transporte; espera de 30 segundos e verificação a cada 5 segundos.
- Disponibilidade inclui socket/timeout/DNS/EOF e SMTP 421. O classificador percorre causas, exceções JavaMail encadeadas e erros individuais, com proteção contra ciclos. SMTP 5xx prevalece em erros mistos, seguindo a regra de DLQ da MS-NOT-08.
- Respostas 450/451/452 não indicam necessariamente indisponibilidade global; continuam na política limitada de retries. Falhas de contrato, preparação e banco não abrem o circuito.
- O gate serializa envios e sondagens por instância e verifica CLOSED antes de enviar. Uma entrega nunca pode servir de teste em HALF_OPEN. A proteção atua por envelope, preservando os templates, privacidade dos destinatários e transação idempotente existentes.
- A falha que abre o circuito também é devolvida sem incrementar tentativa. Mensagens prefetched recebem o mesmo tratamento ou retornam pelo fechamento do canal ao parar o container. Não há ACK de sucesso, nova cópia em retry ou marcador de processamento para essas falhas.
- O listener verifica o gate antes de validar o contrato ou acessar o banco; entregas recebidas durante a pausa são devolvidas imediatamente. Há uma segunda verificação ao enviar, para cobrir abertura durante o processamento em trânsito.
- A parada ocorre no scheduler dedicado, fora da thread do listener que precisa terminar a entrega. Transições solicitam sincronização imediata e a rotina periódica reconcilia o estado novamente, inclusive se o container for iniciado durante OPEN.
- A sonda usa o mesmo `JavaMailSenderImpl`, reaproveitando conexão, autenticação, STARTTLS e timeouts. Não precisa de destinatário fictício nem envia e-mail. Erro de conexão durante HALF_OPEN, inclusive credencial/TLS, mantém o consumo pausado e inicia outra espera.
- Não há transição automática em thread do Resilience4j: a rotina agendada é responsável por adquirir a permissão após a espera. Uma sonda bem-sucedida é suficiente para fechar o circuito.
- Apenas o container pausado pela proteção é retomado. O recurso respeita consumidor explicitamente desabilitado e encerramento do contexto; não inicia consumidores desativados no smoke de infraestrutura.
- Logs registram estado e pausa/retomada, sem corpo, destinatários, credenciais ou texto da exceção SMTP.

## Configuração e limites

Parâmetros e comandos de aplicação estão no [README](../../README.md#circuit-breaker-e-recuperação-smtp--ms-not-09).
Janela/mínimo precisam ser positivos e mínimo não pode exceder a janela; taxa deve estar em (0,100]; durações precisam ser positivas e o intervalo deve ter pelo menos 1 ms. Configuração inválida impede inicialização.

Com parâmetros menos sensíveis que o padrão, falhas anteriores à abertura ainda consomem tentativas da MS-NOT-08. O circuito fica em memória por instância; restart reinicia CLOSED, e uma nova falha pode reabri-lo. RabbitMQ continua sendo a fonte durável de mensagens e tentativas.

A sondagem comprova conexão/TLS/autenticação, não a entrega ao destinatário. Rejeições SMTP por mensagem continuam sob retries/DLQ. A possibilidade de duplicação após aceitação SMTP e antes do commit, ou depois de envio parcial a múltiplos contatos, continua documentada na MS-NOT-05/MS-NOT-07.

Nenhuma migration, mudança de contrato, mudança de topologia ou alteração no backend é necessária.

Evidências: [relatório de testes](../testes/ms-not-09-recuperacao-smtp.md).
