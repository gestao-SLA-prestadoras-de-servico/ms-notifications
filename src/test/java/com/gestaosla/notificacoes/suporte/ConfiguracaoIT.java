package com.gestaosla.notificacoes.suporte;

/** Propriedades da infraestrutura automática ou variáveis dos runners Compose existentes. */
public final class ConfiguracaoIT {
    private ConfiguracaoIT() { }

    public static String valor(String nome) { return valor(nome, null); }

    public static String valor(String nome, String padrao) {
        String valor = System.getProperty(nome, System.getenv(nome));
        if (valor == null) valor = padrao;
        if (valor == null || valor.isBlank()) throw new IllegalStateException("Configure " + nome);
        return valor;
    }
}
