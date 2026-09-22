package com.fraud.project.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.Explanation;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.Transaction;
import com.fraud.project.mlservice.ExplanationResult;
import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.mlservice.PredictionResult;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.ExplanationRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.TransactionRepository;

/**
 * Bir transaction'ı GERÇEKTEN skorlayan taraf — Kafka consumer'ının (bkz.
 * `kafka.TransactionScoringConsumer`) çağırdığı iş mantığı. Önceden
 * `TransactionReplayService` içindeydi; Kafka'ya geçerken üretici (transaction
 * kaydet + event bas) ve tüketici (skorla) sorumlulukları ayrıldı.
 */
@Service
public class TransactionScoringService {

    /** Bkz. TransactionReplayService'teki aynı sabitin açıklaması. */
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final TransactionRepository transactionRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final ExplanationRepository explanationRepository;
    private final AuditLogRepository auditLogRepository;
    private final MlServiceClient mlServiceClient;
    private final RiskFinalizationService riskFinalizationService;

    public TransactionScoringService(
        TransactionRepository transactionRepository,
        RiskScoreRepository riskScoreRepository,
        ExplanationRepository explanationRepository,
        AuditLogRepository auditLogRepository,
        MlServiceClient mlServiceClient,
        RiskFinalizationService riskFinalizationService
    ) {
        this.transactionRepository = transactionRepository;
        this.riskScoreRepository = riskScoreRepository;
        this.explanationRepository = explanationRepository;
        this.auditLogRepository = auditLogRepository;
        this.mlServiceClient = mlServiceClient;
        this.riskFinalizationService = riskFinalizationService;
    }

    @Transactional
    public void score(Long transactionId, Map<String, Object> features) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new NoSuchElementException("Bilinmeyen transaction: " + transactionId));

        PredictionResult prediction = mlServiceClient.predict(features);

        RiskScore riskScore = riskScoreRepository.save(RiskScore.builder()
            .transaction(transaction)
            .fraudProbability(BigDecimal.valueOf(prediction.fraudProbability()))
            .action(prediction.action())
            .modelVersion(prediction.modelVersion())
            .featureSnapshot(features)
            .build());

        ExplanationResult explanation = mlServiceClient.explain(features);
        List<Explanation> explanations = explanation.contributions().stream()
            .map(contribution -> Explanation.builder()
                .riskScore(riskScore)
                .featureName(contribution.featureName())
                .featureValue(contribution.featureValue() == null ? null : String.valueOf(contribution.featureValue()))
                .shapValue(BigDecimal.valueOf(contribution.shapValue()))
                .build())
            .toList();
        explanationRepository.saveAll(explanations);

        auditLogRepository.save(AuditLog.builder()
            .transaction(transaction)
            .riskScore(riskScore)
            .actor(SYSTEM_ACTOR)
            .actionTaken(prediction.action().name())
            .modelVersion(prediction.modelVersion())
            .thresholdReview(BigDecimal.valueOf(prediction.reviewThreshold()))
            .thresholdBlock(BigDecimal.valueOf(prediction.blockThreshold()))
            .build());

        // Rule Engine (ayrı, paralel bir consumer) bu transaction'ı ML'den
        // ÖNCE bitirmiş olabilir — o zaman finalize burada tamamlanır. Aksi
        // halde no-op olur, Rule Engine bitince finalize edecektir.
        riskFinalizationService.tryFinalize(transactionId);
    }

    @Transactional(readOnly = true)
    public TransactionStatusResult getStatus(Long transactionId) {
        if (!transactionRepository.existsById(transactionId)) {
            throw new NoSuchElementException("Bilinmeyen transaction: " + transactionId);
        }

        return riskScoreRepository.findByTransactionId(transactionId)
            .filter(riskScore -> riskScore.getFinalAction() != null)
            .map(riskScore -> new TransactionStatusResult(
                transactionId,
                TransactionStatus.SCORED,
                riskScore.getFraudProbability(),
                riskScore.getFinalAction(),
                riskScore.getModelVersion()
            ))
            .orElseGet(() -> new TransactionStatusResult(
                transactionId, TransactionStatus.PENDING, null, null, null
            ));
    }
}
