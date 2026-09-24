package com.fraud.project.exception;

import java.net.URI;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fraud.project.service.InvalidReviewStateException;

/**
 * Tüm REST katmanı için tek hata çevirme noktası. ResponseEntityExceptionHandler'ı
 * extend ediyoruz ki Spring'in kendi framework hataları (malformed JSON, desteklenmeyen
 * HTTP metodu vb.) de aynı ProblemDetail (RFC 7807) formatında dönsün.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException e, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setInstance(requestPath(request));
        return problem;
    }

    @ExceptionHandler(InvalidReviewStateException.class)
    public ProblemDetail handleInvalidReviewState(InvalidReviewStateException e, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setInstance(requestPath(request));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, WebRequest request) {
        log.error("Beklenmeyen hata [{}]", requestPath(request), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Beklenmeyen bir hata oluştu.");
        problem.setInstance(requestPath(request));
        return problem;
    }

    private URI requestPath(WebRequest request) {
        // WebRequest.getDescription(false) "uri=/api/..." formatında döner.
        return URI.create(request.getDescription(false).replaceFirst("^uri=", ""));
    }
}
