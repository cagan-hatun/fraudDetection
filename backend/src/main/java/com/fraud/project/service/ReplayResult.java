package com.fraud.project.service;

import java.math.BigDecimal;

import com.fraud.project.entity.RiskAction;

public record ReplayResult(
    Long transactionId,
    BigDecimal fraudProbability,
    RiskAction action,
    String modelVersion
) {
}
