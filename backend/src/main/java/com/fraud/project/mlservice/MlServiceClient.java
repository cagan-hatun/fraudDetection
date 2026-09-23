package com.fraud.project.mlservice;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * ml-service'e (FastAPI) REST üzerinden senkron çağrı yapan ince istemci
 * (bkz. backend_decisions: "Spring, ML servisini REST üzerinden senkron çağırır").
 * Çağrılar bir circuit breaker (bkz. ResilienceConfig) ile sarmalanıyor — ml-service
 * sürekli hata veriyorsa devre açılır ve sonraki çağrılar ağı hiç denemeden anında
 * CallNotPermittedException ile başarısız olur.
 */
@Component
public class MlServiceClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public MlServiceClient(RestClient mlServiceRestClient, CircuitBreaker mlServiceCircuitBreaker) {
        this.restClient = mlServiceRestClient;
        this.circuitBreaker = mlServiceCircuitBreaker;
    }

    public PredictionResult predict(Map<String, Object> features) {
        return callProtected(() -> restClient.post()
            .uri("/predict")
            .contentType(MediaType.APPLICATION_JSON)
            .body(features)
            .retrieve()
            .body(PredictionResult.class));
    }

    public ExplanationResult explain(Map<String, Object> features) {
        return callProtected(() -> restClient.post()
            .uri("/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .body(features)
            .retrieve()
            .body(ExplanationResult.class));
    }

    private <T> T callProtected(Supplier<T> call) {
        return circuitBreaker.decorateSupplier(call).get();
    }
}
