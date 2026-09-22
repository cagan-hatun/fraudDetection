package com.fraud.project.kafka;

import java.util.Map;

/**
 * Kafka'daki `transactions` topic'ine basılan event — "bu transaction'ı
 * skorla" komutu. Feature'lar mesajın içinde taşınıyor çünkü `transactions`
 * tablosu (bilinçli olarak) ham feature vektörünü saklamıyor, o sadece
 * skorlama sonrası `risk_scores.feature_snapshot`'a yazılıyor.
 */
public record TransactionScoringEvent(Long transactionId, Map<String, Object> features) {
}
