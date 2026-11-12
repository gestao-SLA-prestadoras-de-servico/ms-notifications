package com.gestaosla.notificacoes.integracoes;

/** Controle de fluxo: manter a entrega original, sem consumir outra tentativa. */
public class SmtpAguardandoRecuperacaoException extends RuntimeException {
    public SmtpAguardandoRecuperacaoException() {
        super("SMTP aguardando recuperação", null, false, false);
    }
}
