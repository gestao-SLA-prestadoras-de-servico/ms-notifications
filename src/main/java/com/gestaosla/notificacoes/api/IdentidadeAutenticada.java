package com.gestaosla.notificacoes.api;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Identidade opaca do destinatário, obtida exclusivamente do JWT validado. */
@Component
public class IdentidadeAutenticada {
    public String sub() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || !StringUtils.hasText(jwt.getToken().getSubject())) {
            throw new AuthenticationCredentialsNotFoundException("Identidade JWT ausente");
        }
        return jwt.getToken().getSubject();
    }
}
