package com.fraud.project.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fraud.project.entity.AnalystReview;
import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.Explanation;
import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.RuleEvaluation;
import com.fraud.project.entity.Transaction;
import com.fraud.project.mlservice.ExplanationResult;
import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.mlservice.PredictionResult;
import com.fraud.project.repository.AnalystReviewRepository;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.ExplanationRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.RuleEvaluationRepository;
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
    private final RuleEvaluationRepository ruleEvaluationRepository;
    private final AnalystReviewRepository analystReviewRepository;
    private final MlServiceClient mlServiceClient;
    private final RiskFinalizationService riskFinalizationService;

    public TransactionScoringService(
        TransactionRepository transactionRepository,
        RiskScoreRepository riskScoreRepository,
        ExplanationRepository explanationRepository,
        AuditLogRepository auditLogRepository,
        RuleEvaluationRepository ruleEvaluationRepository,
        AnalystReviewRepository analystReviewRepository,
        MlServiceClient mlServiceClient,
        RiskFinalizationService riskFinalizationService
    ) {
        this.transactionRepository = transactionRepository;
        this.riskScoreRepository = riskScoreRepository;
        this.explanationRepository = explanationRepository;
        this.auditLogRepository = auditLogRepository;
        this.ruleEvaluationRepository = ruleEvaluationRepository;
        this.analystReviewRepository = analystReviewRepository;
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
        riskScore.setBaseValue(BigDecimal.valueOf(explanation.baseValue()));
        riskScoreRepository.save(riskScore);

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

    /**
     * İşlem Detayı sayfası için tek seferlik, zengin sorgu — polling'in
     * kullandığı getStatus()'tan AYRI, çünkü her 1.5sn'de bir SHAP/rule
     * verisini de çekmek gereksiz yük olurdu. PENDING durumunda ML/Rule
     * Engine/SHAP alanları henüz yoktur, null/boş döner.
     */
    @Transactional(readOnly = true)
    public TransactionDetailResult getDetail(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new NoSuchElementException("Bilinmeyen transaction: " + transactionId));

        Optional<RiskScore> riskScoreOpt = riskScoreRepository.findByTransactionId(transactionId);
        Optional<RuleEvaluation> ruleEvaluationOpt = ruleEvaluationRepository.findByTransactionId(transactionId);

        boolean isFinalized = riskScoreOpt.map(rs -> rs.getFinalAction() != null).orElse(false);

        List<ShapContribution> shapContributions = riskScoreOpt
            .map(rs -> explanationRepository.findByRiskScoreIdOrderByAbsShapValueDesc(rs.getId()).stream()
                .map(e -> new ShapContribution(e.getFeatureName(), e.getFeatureValue(), e.getShapValue()))
                .toList())
            .orElse(List.of());

        AnalystReviewSummary analystReview = analystReviewRepository.findByTransactionId(transactionId)
            .map(r -> new AnalystReviewSummary(r.getDecision(), r.getNote(), r.getReviewedBy(), r.getReviewedAt()))
            .orElse(null);

        return new TransactionDetailResult(
            transactionId,
            isFinalized ? TransactionStatus.SCORED : TransactionStatus.PENDING,
            transaction.getAmount(),
            transaction.getCurrency(),
            transaction.getTransactionTime(),
            transaction.getLocationCountry(),
            transaction.getLocationCity(),
            transaction.getMerchant().getName(),
            transaction.getMerchant().getCategory(),
            transaction.getUser().getExternalRef(),
            transaction.getDevice().getDeviceFingerprint(),
            riskScoreOpt.map(RiskScore::getFraudProbability).orElse(null),
            riskScoreOpt.map(RiskScore::getAction).orElse(null),
            riskScoreOpt.map(RiskScore::getModelVersion).orElse(null),
            riskScoreOpt.map(RiskScore::getBaseValue).orElse(null),
            shapContributions,
            ruleEvaluationOpt.map(RuleEvaluation::getAction).orElse(null),
            ruleEvaluationOpt.map(RuleEvaluation::getMatchedRules).orElse(null),
            isFinalized ? riskScoreOpt.get().getFinalAction() : null,
            analystReview
        );
    }

    /**
     * Bir analistin REVIEW durumundaki bir işlem için karar vermesi. Sadece
     * final karar REVIEW ise izin verilir — APPROVE/BLOCK zaten netleşmiş bir
     * işlemi "review etmek" anlamsız. Aynı işlem için ikinci bir çağrı,
     * önceki kararın ÜZERİNE YAZAR (analist fikrini değiştirebilir).
     */
    @Transactional
    public AnalystReviewSummary submitReview(Long transactionId, String reviewedBy, SubmitReviewRequest request) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new NoSuchElementException("Bilinmeyen transaction: " + transactionId));

        RiskAction finalAction = riskScoreRepository.findByTransactionId(transactionId)
            .map(RiskScore::getFinalAction)
            .orElse(null);

        if (finalAction != RiskAction.REVIEW) {
            throw new InvalidReviewStateException(
                "İşlem #" + transactionId + " şu an REVIEW durumunda değil (nihai karar: " + finalAction + ")");
        }

        AnalystReview review = analystReviewRepository.findByTransactionId(transactionId)
            .orElseGet(() -> AnalystReview.builder().transaction(transaction).build());

        review.setDecision(request.decision());
        review.setNote(request.note());
        review.setReviewedBy(reviewedBy);
        review.setReviewedAt(OffsetDateTime.now());

        AnalystReview saved = analystReviewRepository.save(review);
        return new AnalystReviewSummary(saved.getDecision(), saved.getNote(), saved.getReviewedBy(), saved.getReviewedAt());
    }
}
