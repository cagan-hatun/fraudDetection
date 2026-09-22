package com.fraud.project.service;

public record ReplayAcceptedResult(Long transactionId, TransactionStatus status) {
}
