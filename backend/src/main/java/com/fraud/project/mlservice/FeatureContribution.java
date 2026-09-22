package com.fraud.project.mlservice;

import com.fasterxml.jackson.annotation.JsonProperty;

/** ml-service'in `/explain` cevabındaki tek bir feature'ın SHAP katkısı. */
public record FeatureContribution(
    @JsonProperty("feature_name") String featureName,
    @JsonProperty("feature_value") Object featureValue,
    @JsonProperty("shap_value") double shapValue
) {
}
