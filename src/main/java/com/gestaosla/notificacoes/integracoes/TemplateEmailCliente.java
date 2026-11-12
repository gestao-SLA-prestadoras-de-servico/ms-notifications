package com.gestaosla.notificacoes.integracoes;

import com.gestaosla.notificacoes.eventos.EventoInvalidoException;
import com.gestaosla.notificacoes.eventos.EventoNotificacao;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class TemplateEmailCliente {
    public Conteudo compor(EventoNotificacao evento) {
        String chamado = "chamado " + evento.chamado().numero() + " — " + evento.titulo();
        String chamadoHtml = escapar(chamado);
        String assunto;
        String texto;
        String html;
        switch (evento.tipo()) {
            case PRIMEIRO_POSICIONAMENTO -> {
                assunto = "Primeiro posicionamento";
                texto = "Registramos o primeiro posicionamento do " + chamado + ".\n\n" + evento.textoPosicionamento();
                html = "<p>Registramos o primeiro posicionamento do " + chamadoHtml + ".</p><p>"
                        + escapar(evento.textoPosicionamento()).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>") + "</p>";
            }
            case RESOLUCAO -> {
                assunto = "Chamado resolvido";
                texto = "O " + chamado + " foi marcado como resolvido.\n\nConfira o atendimento no sistema e valide a conclusão. Caso discorde da solução, reabra o chamado no sistema.";
                html = "<p>O " + chamadoHtml + " foi marcado como resolvido.</p><p>Confira o atendimento no sistema e valide a conclusão. Caso discorde da solução, reabra o chamado no sistema.</p>";
            }
            case AVISO_ENCERRAMENTO -> {
                assunto = "Validação automática próxima";
                texto = "O " + chamado + " está próximo da validação e do encerramento automáticos.\n\nConfira a solução no sistema. Caso discorde, reabra o chamado antes do encerramento.";
                html = "<p>O " + chamadoHtml + " está próximo da validação e do encerramento automáticos.</p><p>Confira a solução no sistema. Caso discorde, reabra o chamado antes do encerramento.</p>";
            }
            default -> throw new EventoInvalidoException();
        }
        // Dados do usuário não podem introduzir cabeçalhos adicionais no assunto.
        assunto = ("[FlowSLA] " + assunto + " — " + evento.chamado().numero() + " — " + evento.titulo())
                .replaceAll("[\\r\\n]+", " ");
        return new Conteudo(assunto, "Olá,\n\n" + texto + "\n\nEquipe FlowSLA.",
                "<!doctype html><html lang=\"pt-BR\"><body><p>Olá,</p>" + html + "<p>Equipe FlowSLA.</p></body></html>");
    }

    private String escapar(String texto) {
        return HtmlUtils.htmlEscape(texto, "UTF-8");
    }

    public record Conteudo(String assunto, String texto, String html) {
        @Override
        public String toString() { return "ConteudoEmailCliente"; }
    }
}
