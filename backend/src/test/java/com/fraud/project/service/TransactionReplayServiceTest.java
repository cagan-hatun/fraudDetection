package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.Device;
import com.fraud.project.entity.Explanation;
import com.fraud.project.entity.Merchant;
import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.Transaction;
import com.fraud.project.entity.User;
import com.fraud.project.fixture.DemoFixtureLoader;
import com.fraud.project.fixture.DemoTransactionFixture;
import com.fraud.project.mlservice.ExplanationResult;
import com.fraud.project.mlservice.FeatureContribution;
import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.mlservice.PredictionResult;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.DeviceRepository;
import com.fraud.project.repository.ExplanationRepository;
import com.fraud.project.repository.MerchantRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.TransactionRepository;
import com.fraud.project.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class TransactionReplayServiceTest {

    @Mock private DemoFixtureLoader fixtureLoader;
    @Mock private UserRepository userRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private MerchantRepository merchantRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private RiskScoreRepository riskScoreRepository;
    @Mock private ExplanationRepository explanationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private MlServiceClient mlServiceClient;

    private static final ExplanationResult NO_OP_EXPLANATION =
        new ExplanationResult(0.0, List.of());

    @InjectMocks
    private TransactionReplayService replayService;

    private DemoTransactionFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new DemoTransactionFixture(
            "caught_fraud",
            "Doğru yakalanan fraud",
            true,
            "demo-user-1",
            "demo-device-1",
            "Test Merchant",
            "electronics",
            new BigDecimal("300.00"),
            "USD",
            OffsetDateTime.parse("2026-06-02T03:14:00Z"),
            "US",
            "Miami",
            Map.of("TransactionAmt", 300.0, "ProductCD", "R")
        );
    }

    @Test
    void replay_unknownScenarioId_throws() {
        when(fixtureLoader.findByScenarioId("no_such_scenario")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replayService.replay("no_such_scenario"))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void replay_newUserDeviceMerchant_createsThemBeforeScoring() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));
        when(userRepository.findByExternalRef("demo-user-1")).thenReturn(Optional.empty());
        User savedUser = User.builder().id(1L).externalRef("demo-user-1").build();
        when(userRepository.save(any())).thenReturn(savedUser);

        when(deviceRepository.findByUserAndDeviceFingerprint(savedUser, "demo-device-1"))
            .thenReturn(Optional.empty());
        Device savedDevice = Device.builder().id(1L).user(savedUser).deviceFingerprint("demo-device-1").build();
        when(deviceRepository.save(any())).thenReturn(savedDevice);

        when(merchantRepository.findByName("Test Merchant")).thenReturn(Optional.empty());
        Merchant savedMerchant = Merchant.builder().id(1L).name("Test Merchant").category("electronics").build();
        when(merchantRepository.save(any())).thenReturn(savedMerchant);

        Transaction savedTransaction = Transaction.builder().id(42L).build();
        when(transactionRepository.save(any())).thenReturn(savedTransaction);

        when(mlServiceClient.predict(fixture.features()))
            .thenReturn(new PredictionResult(0.97, RiskAction.BLOCK, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(fixture.features())).thenReturn(NO_OP_EXPLANATION);

        when(riskScoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReplayResult result = replayService.replay("caught_fraud");

        verify(userRepository).save(any());
        verify(deviceRepository).save(any());
        verify(merchantRepository).save(any());

        assertThat(result.transactionId()).isEqualTo(42L);
        assertThat(result.action()).isEqualTo(RiskAction.BLOCK);
        assertThat(result.modelVersion()).isEqualTo("fraud-detection-lightgbm-v1");
        assertThat(result.fraudProbability()).isEqualByComparingTo("0.97");
    }

    @Test
    void replay_existingUserDeviceMerchant_reusesThemInsteadOfCreating() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));

        User existingUser = User.builder().id(5L).externalRef("demo-user-1").build();
        when(userRepository.findByExternalRef("demo-user-1")).thenReturn(Optional.of(existingUser));

        Device existingDevice = Device.builder().id(5L).user(existingUser).deviceFingerprint("demo-device-1").build();
        when(deviceRepository.findByUserAndDeviceFingerprint(existingUser, "demo-device-1"))
            .thenReturn(Optional.of(existingDevice));

        Merchant existingMerchant = Merchant.builder().id(5L).name("Test Merchant").build();
        when(merchantRepository.findByName("Test Merchant")).thenReturn(Optional.of(existingMerchant));

        when(transactionRepository.save(any())).thenReturn(Transaction.builder().id(7L).build());
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.02, RiskAction.APPROVE, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(any())).thenReturn(NO_OP_EXPLANATION);
        when(riskScoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        replayService.replay("caught_fraud");

        verify(userRepository, never()).save(any());
        verify(deviceRepository, never()).save(any());
        verify(merchantRepository, never()).save(any());
    }

    @Test
    void replay_sendsFixtureFeaturesUnmodifiedToMlServiceAndStoresSameSnapshot() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));
        when(userRepository.findByExternalRef(any())).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepository.findByUserAndDeviceFingerprint(any(), any()))
            .thenReturn(Optional.of(Device.builder().id(1L).build()));
        when(merchantRepository.findByName(any())).thenReturn(Optional.of(Merchant.builder().id(1L).build()));
        when(transactionRepository.save(any())).thenReturn(Transaction.builder().id(1L).build());
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.5, RiskAction.REVIEW, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(any())).thenReturn(NO_OP_EXPLANATION);
        when(riskScoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        replayService.replay("caught_fraud");

        verify(mlServiceClient).predict(fixture.features());

        ArgumentCaptor<com.fraud.project.entity.RiskScore> riskScoreCaptor =
            ArgumentCaptor.forClass(com.fraud.project.entity.RiskScore.class);
        verify(riskScoreRepository).save(riskScoreCaptor.capture());
        assertThat(riskScoreCaptor.getValue().getFeatureSnapshot()).isEqualTo(fixture.features());
    }

    @Test
    void replay_savesOneExplanationRowPerFeatureContributionLinkedToTheRiskScore() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));
        when(userRepository.findByExternalRef(any())).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepository.findByUserAndDeviceFingerprint(any(), any()))
            .thenReturn(Optional.of(Device.builder().id(1L).build()));
        when(merchantRepository.findByName(any())).thenReturn(Optional.of(Merchant.builder().id(1L).build()));
        when(transactionRepository.save(any())).thenReturn(Transaction.builder().id(1L).build());
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.9, RiskAction.BLOCK, "fraud-detection-lightgbm-v1", 0.23, 0.99));

        RiskScore savedRiskScore = RiskScore.builder().id(9L).build();
        when(riskScoreRepository.save(any())).thenReturn(savedRiskScore);

        ExplanationResult explanationResult = new ExplanationResult(-1.2, List.of(
            new FeatureContribution("TransactionAmt", 300.0, 0.8),
            new FeatureContribution("ProductCD", "R", -0.3)
        ));
        when(mlServiceClient.explain(fixture.features())).thenReturn(explanationResult);

        replayService.replay("caught_fraud");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Explanation>> explanationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(explanationRepository).saveAll(explanationsCaptor.capture());
        List<Explanation> savedExplanations = explanationsCaptor.getValue();

        assertThat(savedExplanations).hasSize(2);
        assertThat(savedExplanations).allSatisfy(e -> assertThat(e.getRiskScore()).isEqualTo(savedRiskScore));

        Explanation amountExplanation = savedExplanations.stream()
            .filter(e -> e.getFeatureName().equals("TransactionAmt"))
            .findFirst().orElseThrow();
        assertThat(amountExplanation.getFeatureValue()).isEqualTo("300.0");
        assertThat(amountExplanation.getShapValue()).isEqualByComparingTo("0.8");

        Explanation productCdExplanation = savedExplanations.stream()
            .filter(e -> e.getFeatureName().equals("ProductCD"))
            .findFirst().orElseThrow();
        assertThat(productCdExplanation.getFeatureValue()).isEqualTo("R");
        assertThat(productCdExplanation.getShapValue()).isEqualByComparingTo("-0.3");
    }

    @Test
    void replay_emptyContributionsListSavesNoExplanationRows() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));
        when(userRepository.findByExternalRef(any())).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepository.findByUserAndDeviceFingerprint(any(), any()))
            .thenReturn(Optional.of(Device.builder().id(1L).build()));
        when(merchantRepository.findByName(any())).thenReturn(Optional.of(Merchant.builder().id(1L).build()));
        when(transactionRepository.save(any())).thenReturn(Transaction.builder().id(1L).build());
        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.1, RiskAction.APPROVE, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(any())).thenReturn(NO_OP_EXPLANATION);
        when(riskScoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        replayService.replay("caught_fraud");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Explanation>> explanationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(explanationRepository).saveAll(explanationsCaptor.capture());
        assertThat(explanationsCaptor.getValue()).isEmpty();
    }

    @Test
    void replay_savesAuditLogRowWithSystemActorAndThresholds() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));
        when(userRepository.findByExternalRef(any())).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepository.findByUserAndDeviceFingerprint(any(), any()))
            .thenReturn(Optional.of(Device.builder().id(1L).build()));
        when(merchantRepository.findByName(any())).thenReturn(Optional.of(Merchant.builder().id(1L).build()));

        Transaction savedTransaction = Transaction.builder().id(3L).build();
        when(transactionRepository.save(any())).thenReturn(savedTransaction);

        when(mlServiceClient.predict(any()))
            .thenReturn(new PredictionResult(0.65, RiskAction.REVIEW, "fraud-detection-lightgbm-v1", 0.23, 0.99));
        when(mlServiceClient.explain(any())).thenReturn(NO_OP_EXPLANATION);

        RiskScore savedRiskScore = RiskScore.builder().id(4L).build();
        when(riskScoreRepository.save(any())).thenReturn(savedRiskScore);

        replayService.replay("caught_fraud");

        ArgumentCaptor<AuditLog> auditLogCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditLogCaptor.capture());
        AuditLog auditLog = auditLogCaptor.getValue();

        assertThat(auditLog.getTransaction()).isEqualTo(savedTransaction);
        assertThat(auditLog.getRiskScore()).isEqualTo(savedRiskScore);
        assertThat(auditLog.getActor()).isEqualTo("SYSTEM");
        assertThat(auditLog.getActionTaken()).isEqualTo("REVIEW");
        assertThat(auditLog.getModelVersion()).isEqualTo("fraud-detection-lightgbm-v1");
        assertThat(auditLog.getThresholdReview()).isEqualByComparingTo("0.23");
        assertThat(auditLog.getThresholdBlock()).isEqualByComparingTo("0.99");
    }
}
