package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.entity.AnalystDecision;
import com.fraud.project.entity.AnalystReview;
import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.Device;
import com.fraud.project.entity.Explanation;
import com.fraud.project.entity.Merchant;
import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.RuleEvaluation;
import com.fraud.project.entity.Transaction;
import com.fraud.project.entity.User;
import com.fraud.project.mlservice.ExplanationResult;
import com.fraud.project.mlservice.FeatureContribution;
import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.mlservice.PredictionResult;
import com.fraud.project.repository.AnalystReviewRepository;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.ExplanationRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.RuleEvaluationRepository;
import com.fraud.project.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionScoringServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private RiskScoreRepository riskScoreRepository;
    @Mock private ExplanationRepository explanationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private RuleEvaluationRepository ruleEvaluationRepository;
    @Mock private AnalystReviewRepository analystReviewRepository;
    @Mock private MlServiceClient mlServiceClient;
    @Mock private RiskFinalizationService riskFinalizationService;

    @InjectMocks
    private TransactionScoringService scoringService;

    private static final Map<String, Object> FEATURES = Map.of("TransactionAmt", 300.0, "ProductCD", "R");

    @Test
    void score_unknownTransactionId_throws() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scoringService.score(99L, FEATURES))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void score_savesRiskScoreWithExactFeatureSnapshotSent() {
        Transaction transaction = Transaction.builder().id(1L).build();
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(transaction));
        when(mlServiceClient.predict(FEATURES))
            .thenReturn(new PredictionResult(0.97, RiskAction.BLOCK, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(FEATURES)).thenReturn(new ExplanationResult(-1.2, List.of()));
        when(riskScoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scoringService.score(1L, FEATURES);

        // İki kez save() çağrılıyor: predict() sonrası ilk kayıt, explain()
        // sonrası base_value eklenince ikinci (aynı satırın) güncellemesi —
        // ikisi de AYNI RiskScore nesnesini taşıyor, o yüzden son yakalanan
        // değer her iki adımın da sonucunu yansıtır.
        ArgumentCaptor<RiskScore> captor = ArgumentCaptor.forClass(RiskScore.class);
        verify(riskScoreRepository, times(2)).save(captor.capture());
        RiskScore saved = captor.getValue();

        assertThat(saved.getTransaction()).isEqualTo(transaction);
        assertThat(saved.getAction()).isEqualTo(RiskAction.BLOCK);
        assertThat(saved.getModelVersion()).isEqualTo("fraud-detection-lightgbm-v1");
        assertThat(saved.getFraudProbability()).isEqualByComparingTo("0.97");
        assertThat(saved.getFeatureSnapshot()).isEqualTo(FEATURES);
        assertThat(saved.getBaseValue()).isEqualByComparingTo("-1.2");
    }

    @Test
    void score_savesOneExplanationRowPerContributionLinkedToTheRiskScore() {
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(Transaction.builder().id(1L).build()));
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.9, RiskAction.BLOCK, "fraud-detection-lightgbm-v1", 0.23, 0.99));

        RiskScore savedRiskScore = RiskScore.builder().id(9L).build();
        when(riskScoreRepository.save(any())).thenReturn(savedRiskScore);

        when(mlServiceClient.explain(any())).thenReturn(new ExplanationResult(-1.2, List.of(
            new FeatureContribution("TransactionAmt", 300.0, 0.8),
            new FeatureContribution("ProductCD", "R", -0.3)
        )));

        scoringService.score(1L, FEATURES);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Explanation>> captor = ArgumentCaptor.forClass(List.class);
        verify(explanationRepository).saveAll(captor.capture());
        List<Explanation> saved = captor.getValue();

        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(e -> assertThat(e.getRiskScore()).isEqualTo(savedRiskScore));
        assertThat(saved).extracting(Explanation::getFeatureName).containsExactlyInAnyOrder("TransactionAmt", "ProductCD");
    }

    @Test
    void score_savesAuditLogRowWithSystemActorAndThresholds() {
        Transaction transaction = Transaction.builder().id(3L).build();
        when(transactionRepository.findById(3L)).thenReturn(Optional.of(transaction));
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.65, RiskAction.REVIEW, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(any())).thenReturn(new ExplanationResult(0.0, List.of()));

        RiskScore savedRiskScore = RiskScore.builder().id(4L).build();
        when(riskScoreRepository.save(any())).thenReturn(savedRiskScore);

        scoringService.score(3L, FEATURES);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();

        assertThat(auditLog.getTransaction()).isEqualTo(transaction);
        assertThat(auditLog.getRiskScore()).isEqualTo(savedRiskScore);
        assertThat(auditLog.getActor()).isEqualTo("SYSTEM");
        assertThat(auditLog.getActionTaken()).isEqualTo("REVIEW");
        assertThat(auditLog.getThresholdReview()).isEqualByComparingTo("0.23");
        assertThat(auditLog.getThresholdBlock()).isEqualByComparingTo("0.99");
    }

    @Test
    void score_triggersFinalizationAttemptAfterSavingRiskScore() {
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(Transaction.builder().id(1L).build()));
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.5, RiskAction.REVIEW, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(any())).thenReturn(new ExplanationResult(0.0, List.of()));
        when(riskScoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scoringService.score(1L, FEATURES);

        // ML tarafı Rule Engine'den ÖNCE ya da SONRA bitebilir — her iki
        // durumda da kendi bittiğinde finalize denemesi tetiklemeli
        // (RiskFinalizationService no-op yapar eğer Rule Engine henüz
        // bitmediyse).
        verify(riskFinalizationService).tryFinalize(1L);
    }

    @Test
    void getStatus_unknownTransactionId_throws() {
        when(transactionRepository.existsById(42L)).thenReturn(false);

        assertThatThrownBy(() -> scoringService.getStatus(42L))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getStatus_noRiskScoreYet_returnsPendingWithNullScoreFields() {
        when(transactionRepository.existsById(5L)).thenReturn(true);
        when(riskScoreRepository.findByTransactionId(5L)).thenReturn(Optional.empty());

        TransactionStatusResult result = scoringService.getStatus(5L);

        assertThat(result.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.fraudProbability()).isNull();
        assertThat(result.action()).isNull();
        assertThat(result.modelVersion()).isNull();
    }

    @Test
    void getStatus_riskScoreExistsButNotYetFinalized_stillReturnsPending() {
        // ML bitmiş (risk_score var) ama Rule Engine henüz bitmediği için
        // finalAction hâlâ null — istemciye göre bu hâlâ PENDING'tir, ara
        // durumları dışarı sızdırmıyoruz.
        RiskScore riskScore = RiskScore.builder()
            .fraudProbability(new java.math.BigDecimal("0.87"))
            .action(RiskAction.REVIEW)
            .modelVersion("fraud-detection-lightgbm-v1")
            .build();
        when(transactionRepository.existsById(6L)).thenReturn(true);
        when(riskScoreRepository.findByTransactionId(6L)).thenReturn(Optional.of(riskScore));

        TransactionStatusResult result = scoringService.getStatus(6L);

        assertThat(result.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void getStatus_finalized_returnsScoredWithFinalAction() {
        RiskScore riskScore = RiskScore.builder()
            .fraudProbability(new java.math.BigDecimal("0.87"))
            .action(RiskAction.REVIEW)
            .finalAction(RiskAction.BLOCK)
            .modelVersion("fraud-detection-lightgbm-v1")
            .build();
        when(transactionRepository.existsById(6L)).thenReturn(true);
        when(riskScoreRepository.findByTransactionId(6L)).thenReturn(Optional.of(riskScore));

        TransactionStatusResult result = scoringService.getStatus(6L);

        assertThat(result.status()).isEqualTo(TransactionStatus.SCORED);
        assertThat(result.fraudProbability()).isEqualByComparingTo("0.87");
        // finalAction (Rule Engine ile escalate olmuş) dönüyor, ML'in HAM
        // action'ı (REVIEW) değil.
        assertThat(result.action()).isEqualTo(RiskAction.BLOCK);
        assertThat(result.modelVersion()).isEqualTo("fraud-detection-lightgbm-v1");
    }

    @Test
    void getDetail_unknownTransactionId_throws() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scoringService.getDetail(99L))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getDetail_noRiskScoreYet_returnsTransactionFactsWithNullScoringFields() {
        Transaction transaction = transactionWithFullRelations(7L);
        when(transactionRepository.findById(7L)).thenReturn(Optional.of(transaction));
        when(riskScoreRepository.findByTransactionId(7L)).thenReturn(Optional.empty());
        when(ruleEvaluationRepository.findByTransactionId(7L)).thenReturn(Optional.empty());
        when(analystReviewRepository.findByTransactionId(7L)).thenReturn(Optional.empty());

        TransactionDetailResult result = scoringService.getDetail(7L);

        assertThat(result.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.amount()).isEqualByComparingTo("40.00");
        assertThat(result.merchantName()).isEqualTo("Corner Cafe");
        assertThat(result.userExternalRef()).isEqualTo("demo-user-1");
        assertThat(result.deviceFingerprint()).isEqualTo("demo-device-1");
        assertThat(result.fraudProbability()).isNull();
        assertThat(result.mlAction()).isNull();
        assertThat(result.ruleAction()).isNull();
        assertThat(result.finalAction()).isNull();
        assertThat(result.shapContributions()).isEmpty();
        assertThat(result.analystReview()).isNull();
    }

    @Test
    void getDetail_finalized_returnsFullBreakdownWithShapSortedByAbsoluteValue() {
        Transaction transaction = transactionWithFullRelations(8L);
        when(transactionRepository.findById(8L)).thenReturn(Optional.of(transaction));

        RiskScore riskScore = RiskScore.builder()
            .id(20L)
            .fraudProbability(new BigDecimal("0.995"))
            .action(RiskAction.BLOCK)
            .finalAction(RiskAction.BLOCK)
            .modelVersion("fraud-detection-lightgbm-v1")
            .baseValue(new BigDecimal("-1.2"))
            .build();
        when(riskScoreRepository.findByTransactionId(8L)).thenReturn(Optional.of(riskScore));

        RuleEvaluation ruleEvaluation = RuleEvaluation.builder()
            .action(RiskAction.APPROVE)
            .matchedRules(null)
            .build();
        when(ruleEvaluationRepository.findByTransactionId(8L)).thenReturn(Optional.of(ruleEvaluation));

        // Repository zaten |shap_value|'ya göre azalan sırada döndürüyor
        // (bkz. ExplanationRepository JPQL sorgusu) — servis bunu olduğu
        // gibi taşımalı, kendisi yeniden sıralamamalı.
        when(explanationRepository.findByRiskScoreIdOrderByAbsShapValueDesc(20L)).thenReturn(List.of(
            Explanation.builder().featureName("new_device").featureValue("true").shapValue(new BigDecimal("1.86")).build(),
            Explanation.builder().featureName("merchant_risk").featureValue("0.065").shapValue(new BigDecimal("-0.24")).build()
        ));

        OffsetDateTime reviewedAt = OffsetDateTime.parse("2026-06-16T09:00:00Z");
        AnalystReview review = AnalystReview.builder()
            .decision(AnalystDecision.REJECTED)
            .note("Müşteriyle telefonla teyit edilemedi.")
            .reviewedBy("analyst")
            .reviewedAt(reviewedAt)
            .build();
        when(analystReviewRepository.findByTransactionId(8L)).thenReturn(Optional.of(review));

        TransactionDetailResult result = scoringService.getDetail(8L);

        assertThat(result.status()).isEqualTo(TransactionStatus.SCORED);
        assertThat(result.fraudProbability()).isEqualByComparingTo("0.995");
        assertThat(result.mlAction()).isEqualTo(RiskAction.BLOCK);
        assertThat(result.ruleAction()).isEqualTo(RiskAction.APPROVE);
        assertThat(result.finalAction()).isEqualTo(RiskAction.BLOCK);
        assertThat(result.baseValue()).isEqualByComparingTo("-1.2");
        assertThat(result.shapContributions()).extracting(ShapContribution::featureName)
            .containsExactly("new_device", "merchant_risk");

        // getDetail, daha önce verilmiş bir analist kararını da (varsa) taşımalı.
        assertThat(result.analystReview()).isNotNull();
        assertThat(result.analystReview().decision()).isEqualTo(AnalystDecision.REJECTED);
        assertThat(result.analystReview().reviewedBy()).isEqualTo("analyst");
        assertThat(result.analystReview().reviewedAt()).isEqualTo(reviewedAt);
    }

    @Test
    void submitReview_unknownTransactionId_throws() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scoringService.submitReview(99L, "analyst", new SubmitReviewRequest(AnalystDecision.APPROVED, null)))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void submitReview_transactionNotInReviewState_throwsInvalidReviewState() {
        Transaction transaction = transactionWithFullRelations(10L);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transaction));

        RiskScore riskScore = RiskScore.builder().finalAction(RiskAction.BLOCK).build();
        when(riskScoreRepository.findByTransactionId(10L)).thenReturn(Optional.of(riskScore));

        assertThatThrownBy(
            () -> scoringService.submitReview(10L, "analyst", new SubmitReviewRequest(AnalystDecision.APPROVED, null)))
            .isInstanceOf(InvalidReviewStateException.class);
    }

    @Test
    void submitReview_noRiskScoreYet_treatedAsNotReviewable() {
        // finalAction henüz yok (skorlama bitmedi) — REVIEW değil, o yüzden
        // reddedilmeli. Erken/yanlışlıkla gönderilen bir karardan sistemi korur.
        Transaction transaction = transactionWithFullRelations(11L);
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(transaction));
        when(riskScoreRepository.findByTransactionId(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(
            () -> scoringService.submitReview(11L, "analyst", new SubmitReviewRequest(AnalystDecision.APPROVED, null)))
            .isInstanceOf(InvalidReviewStateException.class);
    }

    @Test
    void submitReview_newDecision_savesReviewLinkedToTransaction() {
        Transaction transaction = transactionWithFullRelations(12L);
        when(transactionRepository.findById(12L)).thenReturn(Optional.of(transaction));

        RiskScore riskScore = RiskScore.builder().finalAction(RiskAction.REVIEW).build();
        when(riskScoreRepository.findByTransactionId(12L)).thenReturn(Optional.of(riskScore));
        when(analystReviewRepository.findByTransactionId(12L)).thenReturn(Optional.empty());
        when(analystReviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnalystReviewSummary summary = scoringService.submitReview(
            12L, "analyst", new SubmitReviewRequest(AnalystDecision.APPROVED, "Müşteriyle teyit edildi."));

        ArgumentCaptor<AnalystReview> captor = ArgumentCaptor.forClass(AnalystReview.class);
        verify(analystReviewRepository).save(captor.capture());
        AnalystReview saved = captor.getValue();

        assertThat(saved.getTransaction()).isEqualTo(transaction);
        assertThat(saved.getDecision()).isEqualTo(AnalystDecision.APPROVED);
        assertThat(saved.getNote()).isEqualTo("Müşteriyle teyit edildi.");
        assertThat(saved.getReviewedBy()).isEqualTo("analyst");
        assertThat(saved.getReviewedAt()).isNotNull();

        assertThat(summary.decision()).isEqualTo(AnalystDecision.APPROVED);
        assertThat(summary.reviewedBy()).isEqualTo("analyst");
    }

    @Test
    void submitReview_existingDecision_overwritesInPlaceInsteadOfCreatingDuplicate() {
        Transaction transaction = transactionWithFullRelations(13L);
        when(transactionRepository.findById(13L)).thenReturn(Optional.of(transaction));

        RiskScore riskScore = RiskScore.builder().finalAction(RiskAction.REVIEW).build();
        when(riskScoreRepository.findByTransactionId(13L)).thenReturn(Optional.of(riskScore));

        AnalystReview existing = AnalystReview.builder()
            .id(77L)
            .transaction(transaction)
            .decision(AnalystDecision.APPROVED)
            .reviewedBy("analyst")
            .reviewedAt(OffsetDateTime.parse("2026-06-16T09:00:00Z"))
            .build();
        when(analystReviewRepository.findByTransactionId(13L)).thenReturn(Optional.of(existing));
        when(analystReviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Analist fikrini değiştirip RED'e çeviriyor.
        scoringService.submitReview(13L, "analyst", new SubmitReviewRequest(AnalystDecision.REJECTED, "Fikrimi değiştirdim."));

        ArgumentCaptor<AnalystReview> captor = ArgumentCaptor.forClass(AnalystReview.class);
        verify(analystReviewRepository).save(captor.capture());
        AnalystReview saved = captor.getValue();

        // AYNI satır (id=77) güncellendi, yeni bir satır oluşturulmadı.
        assertThat(saved.getId()).isEqualTo(77L);
        assertThat(saved.getDecision()).isEqualTo(AnalystDecision.REJECTED);
        assertThat(saved.getNote()).isEqualTo("Fikrimi değiştirdim.");
    }

    private static Transaction transactionWithFullRelations(Long id) {
        User user = User.builder().id(1L).externalRef("demo-user-1").build();
        Device device = Device.builder().id(1L).user(user).deviceFingerprint("demo-device-1").build();
        Merchant merchant = Merchant.builder().id(1L).name("Corner Cafe").category("food").build();
        return Transaction.builder()
            .id(id)
            .user(user)
            .device(device)
            .merchant(merchant)
            .amount(new BigDecimal("40.00"))
            .currency("USD")
            .transactionTime(OffsetDateTime.parse("2026-06-10T08:20:00Z"))
            .locationCountry("US")
            .locationCity("Seattle")
            .build();
    }
}
