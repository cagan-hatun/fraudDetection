package com.fraud.project.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * `POST /api/transactions` için istek gövdesi — `/api/demo/replay/{scenarioId}`'in
 * aksine sabit bir fixture'a bakmaz, çağıran taraf TÜM feature vektörünü
 * (`features`) kendisi sağlar. Bilinçli tasarım: IEEE-CIS'in ~100 anonim
 * V/C/D sütunu, orijinal verinin nasıl anonimleştirildiği bilinmediği için
 * sıfırdan hesaplanamıyor (bkz. ml/README.md'deki feature seçimi deneyi —
 * bu sütunlar olmadan PR-AUC ~0.14'e düşüyor). Gerçek bir sistemde bu vektör
 * ayrı bir feature store/pipeline'dan gelirdi; bu endpoint o sınırın NEREDE
 * çizildiğini dürüstçe gösteriyor — "backend keyfi bir transaction'ı gerçek
 * pipeline'dan (Kafka→ML+Rules→merge) geçirebilir", "backend sıfırdan
 * anonim feature hesaplayabilir" DEĞİL.
 *
 * Not: `amount` (domain, `transactions` tablosuna yazılır) ile `features`
 * içindeki `TransactionAmt` (modele giden ham değer) OTOMATİK
 * SENKRONİZE EDİLMEZ — fixture'larda da aynı ayrım var. Tutarlılık
 * çağıranın sorumluluğunda.
 */
public record SubmitTransactionRequest(
    @NotBlank String userExternalRef,
    @NotBlank String deviceFingerprint,
    @NotBlank String merchantName,
    String merchantCategory,
    @NotNull @Positive BigDecimal amount,
    @NotBlank @Size(min = 3, max = 3) String currency,
    // Verilmezse ingest anında (şimdi) zaman damgalanır.
    OffsetDateTime transactionTime,
    @NotBlank String locationCountry,
    @NotBlank String locationCity,
    @NotEmpty Map<String, Object> features
) {
}
