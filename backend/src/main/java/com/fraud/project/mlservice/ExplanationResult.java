package com.fraud.project.mlservice;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** ml-service'in `/explain` cevabının Java karşılığı. */
public record ExplanationResult(
    @JsonProperty("base_value") double baseValue,
    List<FeatureContribution> contributions
) {
}
