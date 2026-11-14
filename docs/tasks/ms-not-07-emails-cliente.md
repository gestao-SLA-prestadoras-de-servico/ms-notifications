# MS-NOT-07 — Implementar os e-mails ao cliente

Requisitos: RF-52; RN-08, RN-33, RN-34, RN-41 e RN-53.
Depende das MS-NOT-03 e MS-NOT-05 e dos eventos do produtor.

| Critério / passo | Implementação |
|---|---|
| Três assuntos/conteúdos próprios | `TemplateEmailCliente`: primeiro posicionamento, resolução e aviso de encerramento |
| Destinatário recebido no evento | `EnviadorEmailCliente`: um envelope por endereço distinto, sem consultar cadastro ou API |
| Texto do gestor | Texto/HTML de primeiro posicionamento incluem `textoPosicionamento` validado pelo contrato |
| Inspeção Mailpit | SMTP configurado por ambiente, Compose com Mailpit e suíte que inspeciona mensagens pela API |
| Consumidor da fila de e-mail | `ConsumidorEmailCliente`, listener `notificacoesEmail`, fila padrão `notificacoes.email`, ACK manual |
| Número/título presentes | Incluídos no assunto e nos dois formatos do corpo |
| Escape HTML | Escape de número, título e posicionamento; quebras do posicionamento preservadas como `<br>` |
| Reentrega e sucesso | Reuso da coordenação da MS-NOT-05, marcador por evento/canal depois dos envios e ACK após commit |

## Decisões

- Templates em Java, sem motor/dependência adicional: três conteúdos pequenos com versões texto/HTML em UTF-8, geradas pelo mesmo método e entregues como alternativas MIME.
- O envio SMTP exige transação ativa (`MANDATORY`), iniciada pelo coordenador. O SMTP continua um efeito externo sem rollback: enviar e gravar marcador não constituem uma transação distribuída.
- Um envelope por contato mantém os destinatários separados no cabeçalho `To`. Endereços repetidos exatamente na lista são consolidados; o produtor deve preservar ID/conteúdo nas republicações.
- O aviso não promete uma data de entrega nem agenda jobs. A RN-34 determina que o produtor emita o evento um dia útil antes do encerramento; uma entrega atrasada não pode dizer “amanhã”.
- CR/LF são retirados do assunto para impedir cabeçalhos adicionais. Em HTML, dados do usuário são escapados antes de inserir quebras de linha; em texto simples, o conteúdo é preservado.
- O remetente é configurável por `MAIL_FROM`, com padrão local de desenvolvimento. Credenciais, autenticação, STARTTLS obrigatório quando habilitado e três timeouts de 5 segundos ficam no ambiente.
- Nenhuma entidade/migration é alterada. E-mail e texto de posicionamento permanecem somente no evento e no envio, sem registro no banco ou nos logs do listener. `Conteudo.toString()` também omite o conteúdo.
- Erros de contrato/canal são rejeitados sem requeue para a DLQ. Falhas de SMTP/preparação/banco pedem reentrega sem gravar sucesso; classificação de erros, limite/backoff e circuit breaker continuam para MS-NOT-08/MS-NOT-09.
- Os testes anteriores deixam o novo listener desligado. A suíte de mensageria inclui Mailpit e uma nova suíte com SMTP real e os dois listeners ativos para comprovar isolamento.

## Limitação de duplicação SMTP

Se o SMTP aceitar um envio e a aplicação cair ou o commit do marcador falhar,
o mesmo e-mail poderá ser enviado na reentrega. Se houver vários contatos e o
envio falhar no meio, os já atendidos também podem receber novamente. O lock
impede execução simultânea cooperativa e o marcador bloqueia eventos concluídos;
isso não elimina a janela entre aceitação SMTP e confirmação no banco.

## Atualização e validação

Reconstruir/iniciar a imagem habilita o listener de e-mail por padrão. Eventos
pendentes válidos começarão a ser enviados ao SMTP configurado. O Compose usa
Mailpit; configurar provedor externo exige definir endpoint, remetente e opções
de autenticação/TLS no ambiente da implantação.

Sem alteração de schema, credenciais existentes ou arquivos do backend.
Esta task não valida o publicador do app-principal ou a conexão entre Composes.

Comandos e variáveis: [README](../../README.md).
Resultados: [relatório de testes](../testes/ms-not-07-emails-cliente.md).
