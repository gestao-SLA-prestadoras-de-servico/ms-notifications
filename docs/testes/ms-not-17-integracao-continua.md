# Testes — MS-NOT-17

O workflow executa `bash ./mvnw -B -ntp -Pcompleto-it verify` e `docker build --tag ms-notificacoes:ci .` em sequência. A etapa de relatórios usa `always()` para disponibilizar resultados também quando a compilação ou os testes falham. O resultado real do GitHub Actions depende de executar o workflow no repositório remoto; a configuração local não comprova a execução no runner hospedado.

Em 13/11/2026, o comando Maven equivalente no Windows com Java 21 terminou com BUILD SUCCESS: 161 testes locais e 84 de integração, sem falhas ou testes ignorados. Log local: `target/ms-not-17-ci.log`.

O `docker build --tag ms-notificacoes:ci .` também terminou com sucesso no Docker local; log em `target/ms-not-17-docker.log`. A execução real no GitHub Actions e o upload remoto dos artefatos ainda não foram verificados.
