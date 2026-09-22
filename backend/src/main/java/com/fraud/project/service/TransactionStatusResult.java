package com.fraud.project.service;

import java.math.BigDecimal;

import com.fraud.project.entity.RiskAction;

public record TransactionStatusResult(
    Long transactionId,
    TransactionStatus status,
    BigDecimal fraudProbability,
    RiskAction action,
    String modelVersion
) {
}
