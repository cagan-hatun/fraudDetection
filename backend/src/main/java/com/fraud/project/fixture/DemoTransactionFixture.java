package com.fraud.project.fixture;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * IEEE-CIS holdout setinden export edilmiş, gerçek (sentetik ÜRETİLMEMİŞ) bir
 * demo işlemi. `features`, ml-service'in /predict ve /explain'inin beklediği
 * 120 sütunluk tam vektördür — anahtar isimleri final_features ile birebir
 * eşleşmeli (bkz. ml/models/fraud_lightgbm_v1_metadata.json).
 *
 * Bu sınıf sadece JSON'u okumak için bir DTO — veritabanına yazılmaz,
 * pipeline'ın GİRDİSİdir (bkz. database_decisions memory'sindeki "fixture
 * DB'nin çıktısı, JSON'un girdisi olmalı" kararı).
 */
public record DemoTransactionFixture(
    String scenarioId,
    String label,
    boolean groundTruthIsFraud,
    String userExternalRef,
    String deviceFingerprint,
    String merchantName,
    String merchantCategory,
    BigDecimal amount,
    String currency,
    OffsetDateTime transactionTime,
    String locationCountry,
    String locationCity,
    Map<String, Object> features
) {
}
