package com.fraud.project.mlservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fraud.project.entity.RiskAction;

/** ml-service'in `/predict` cevabının Java karşılığı (snake_case JSON alanları). */
public record PredictionResult(
    @JsonProperty("fraud_probability") double fraudProbability,
    RiskAction action,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("review_threshold") double reviewThreshold,
    @JsonProperty("block_threshold") double blockThreshold
) {
}
