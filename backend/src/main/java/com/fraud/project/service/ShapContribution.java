package com.fraud.project.service;

import java.math.BigDecimal;

public record ShapContribution(
    String featureName,
    String featureValue,
    BigDecimal shapValue
) {
}
