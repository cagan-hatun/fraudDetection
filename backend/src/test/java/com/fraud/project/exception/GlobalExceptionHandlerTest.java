package com.fraud.project.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.WebRequest;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock private WebRequest webRequest;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundExceptionMapsTo404WithMessageAndPath() {
        when(webRequest.getDescription(false)).thenReturn("uri=/api/demo/replay/unknown");

        ProblemDetail problem = handler.handleNotFound(
            new NoSuchElementException("Bilinmeyen demo senaryosu: unknown"), webRequest);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getDetail()).isEqualTo("Bilinmeyen demo senaryosu: unknown");
        assertThat(problem.getInstance()).hasToString("/api/demo/replay/unknown");
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingInternalMessage() {
        when(webRequest.getDescription(false)).thenReturn("uri=/api/demo/scenarios");

        ProblemDetail problem = handler.handleUnexpected(
            new RuntimeException("connection refused: password=hunter2"), webRequest);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).doesNotContain("hunter2");
        assertThat(problem.getInstance()).hasToString("/api/demo/scenarios");
    }
}
