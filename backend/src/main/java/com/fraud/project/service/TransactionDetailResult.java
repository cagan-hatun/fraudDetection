package com.fraud.project.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.fraud.project.entity.RiskAction;

/**
 * İşlem Detayı sayfası için tek seferlik, zengin cevap — polling için
 * kullanılan (hafif) TransactionStatusResult'tan bilinçli olarak AYRI.
 * PENDING durumunda ML/Rule Engine/SHAP alanları henüz yok, hepsi null/boş.
 */
public record TransactionDetailResult(
    Long transactionId,
    TransactionStatus status,

    // İşlem gerçekleri — replay anında yazılır, PENDING'te bile mevcuttur.
    BigDecimal amount,
    String currency,
    OffsetDateTime transactionTime,
    String locationCountry,
    String locationCity,
    String merchantName,
    String merchantCategory,
    String userExternalRef,
    String deviceFingerprint,

    // ML Modeli — sadece skorlama bittiyse dolu.
    BigDecimal fraudProbability,
    RiskAction mlAction,
    String modelVersion,
    BigDecimal baseValue,
    List<ShapContribution> shapContributions,

    // Rule Engine — sadece kendi değerlendirmesi bittiyse dolu.
    RiskAction ruleAction,
    String matchedRules,

    // Nihai karar — ikisi de bitip escalate-only birleştirme tamamlanınca dolu.
    RiskAction finalAction,

    // Analistin REVIEW kararı — henüz verilmediyse null. Sistemin kararını
    // (finalAction) asla değiştirmez, ayrı bir bilgi katmanıdır.
    AnalystReviewSummary analystReview
) {
}
