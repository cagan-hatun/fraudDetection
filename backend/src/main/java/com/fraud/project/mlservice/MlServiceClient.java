package com.fraud.project.mlservice;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ml-service'e (FastAPI) REST üzerinden senkron çağrı yapan ince istemci
 * (bkz. backend_decisions: "Spring, ML servisini REST üzerinden senkron çağırır").
 */
@Component
public class MlServiceClient {

    private final RestClient restClient;

    public MlServiceClient(RestClient mlServiceRestClient) {
        this.restClient = mlServiceRestClient;
    }

    public PredictionResult predict(Map<String, Object> features) {
        return restClient.post()
            .uri("/predict")
            .contentType(MediaType.APPLICATION_JSON)
            .body(features)
            .retrieve()
            .body(PredictionResult.class);
    }

    public ExplanationResult explain(Map<String, Object> features) {
        return restClient.post()
            .uri("/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .body(features)
            .retrieve()
            .body(ExplanationResult.class);
    }
}
