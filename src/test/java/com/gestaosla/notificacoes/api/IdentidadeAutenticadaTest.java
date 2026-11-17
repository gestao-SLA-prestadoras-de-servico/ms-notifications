package com.gestaosla.notificacoes.api;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class IdentidadeAutenticadaTest {
    @AfterEach void limpar() { SecurityContextHolder.clearContext(); }

    @Test void naoAceitaAusenciaDeJwtNemAutenticacaoDeSessao() {
        var identidade = new IdentidadeAutenticada();
        assertThatThrownBy(identidade::sub).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("usuario", "", List.of()));
        assertThatThrownBy(identidade::sub).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
