package com.gestaosla.notificacoes.suporte;

/**
 * O contrato do backend exige uuid em {@code eventoId} e nos subs. Os testes que contam eventos por grupo
 * ({@code like 'carga-%'}) usam um uuid cujo primeiro bloco identifica o grupo e o último, o número.
 */
public final class IdsTeste {
    private IdsTeste() { }

    /** {@code "carga-7"} vira o uuid do grupo {@code "carga"} com número 7; sem número final, o grupo é o rótulo todo. */
    public static String uuid(String rotulo) {
        int traco = rotulo.lastIndexOf('-');
        String sufixo = rotulo.substring(traco + 1);
        boolean numerado = traco > 0 && !sufixo.isEmpty() && sufixo.chars().allMatch(Character::isDigit);
        String grupo = numerado ? rotulo.substring(0, traco) : rotulo;
        return String.format("%08x-0000-4000-8000-%012d", grupo.hashCode(), numerado ? Long.parseLong(sufixo) : 0);
    }

    /** Padrão {@code like} de todos os uuids do grupo. */
    public static String padrao(String grupo) {
        return String.format("%08x-%%", grupo.hashCode());
    }
}
