package com.fraud.project.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * `/api/auth/login`, JWT GEREKTİRMEYEN (permitAll) tek endpoint — bu yüzden
 * brute-force denemesine en açık nokta da o. IP başına dakikada
 * {@value #CAPACITY} deneme sınırı (token bucket, bucket4j) koyuyoruz; aşılırsa
 * 429 + RFC 7807 benzeri bir gövde dönüyoruz. Bu bir Spring MVC handler'ı
 * DEĞİL, ham bir servlet filtresi olduğu için `GlobalExceptionHandler` devreye
 * giremiyor — gövdeyi elle yazıyoruz.
 *
 * Bellek içi bucket'lar, tek instance için yeterli (bkz. `ResilienceConfig`'in
 * aynı "portfolyo ölçeğinde dağıtık bir çözüm gereksiz" gerekçesi) — birden
 * çok backend instance'ı olsaydı paylaşılan bir store (Redis) gerekirdi.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final int CAPACITY = 5;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final String LOGIN_PATH = "/api/auth/login";

    private final ConcurrentHashMap<String, Bucket> bucketsByIp = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public LoginRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isLoginRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = bucketsByIp.computeIfAbsent(request.getRemoteAddr(), ip -> newBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = Map.of(
            "status", 429,
            "title", "Too Many Requests",
            "detail", "Çok fazla giriş denemesi yapıldı, lütfen bir dakika sonra tekrar deneyin.",
            "instance", request.getRequestURI()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI());
    }

    private Bucket newBucket() {
        return Bucket.builder()
            .addLimit(limit -> limit.capacity(CAPACITY).refillGreedy(CAPACITY, REFILL_PERIOD))
            .build();
    }
}
