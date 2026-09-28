package com.example.failurelab.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 ProblemDetail. Spring's own exceptions (400s, 404s, 405s...) are
 * handled by the superclass; anything else becomes a 500 whose body carries only the exception type and
 * a correlation id. The stack trace goes to the log, never to the client.
 *
 * This does NOT hide failures: broken scenarios still return their natural 500s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        String correlationId = MDC.get(ScenarioHeaderFilter.CORRELATION_ID);
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected " + ex.getClass().getSimpleName() + "; search the logs for the correlationId");
        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
