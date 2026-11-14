package com.gestaosla.notificacoes.api;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = NotificacoesController.class)
public class NotificacoesExceptionHandler {
    @ExceptionHandler(NotificacaoNaoEncontradaException.class)
    ProblemDetail naoEncontrada(HttpServletRequest request) {
        return problema(HttpStatus.NOT_FOUND, "Notificação não encontrada", "Notificação não encontrada.", request);
    }

    @ExceptionHandler({PaginacaoInvalidaException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail parametroInvalido(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "Parâmetros inválidos",
                "Informe um UUID válido no caminho e paginação com page >= 0, size de 1 a 100 e deslocamento até 2147483647.", request);
    }

    private ProblemDetail problema(HttpStatus status, String titulo, String detalhe, HttpServletRequest request) {
        var problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(request.getRequestURI()));
        return problema;
    }
}
