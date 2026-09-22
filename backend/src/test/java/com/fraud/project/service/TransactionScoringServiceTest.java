package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.Explanation;
import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.Transaction;
import com.fraud.project.mlservice.ExplanationResult;
import com.fraud.project.mlservice.FeatureContribution;
import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.mlservice.PredictionResult;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.ExplanationRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionScoringServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private RiskScoreRepository riskScoreRepository;
    @Mock private ExplanationRepository explanationRepository;
    @Mock private AuditLogRepository auditLogRepository;
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
        when(mlServiceClient.explain(FEATURES)).thenReturn(new ExplanationResult(0.0, List.of()));
        when(riskScoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scoringService.score(1L, FEATURES);

        ArgumentCaptor<RiskScore> captor = ArgumentCaptor.forClass(RiskScore.class);
        verify(riskScoreRepository).save(captor.capture());
        RiskScore saved = captor.getValue();

        assertThat(saved.getTransaction()).isEqualTo(transaction);
        assertThat(saved.getAction()).isEqualTo(RiskAction.BLOCK);
        assertThat(saved.getModelVersion()).isEqualTo("fraud-detection-lightgbm-v1");
        assertThat(saved.getFraudProbability()).isEqualByComparingTo("0.97");
        assertThat(saved.getFeatureSnapshot()).isEqualTo(FEATURES);
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
}
