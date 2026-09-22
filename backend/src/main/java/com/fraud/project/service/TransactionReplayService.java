package com.fraud.project.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.Device;
import com.fraud.project.entity.Explanation;
import com.fraud.project.entity.Merchant;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.Transaction;
import com.fraud.project.entity.User;
import com.fraud.project.fixture.DemoFixtureLoader;
import com.fraud.project.fixture.DemoTransactionFixture;
import com.fraud.project.mlservice.ExplanationResult;
import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.mlservice.PredictionResult;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.DeviceRepository;
import com.fraud.project.repository.ExplanationRepository;
import com.fraud.project.repository.MerchantRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.TransactionRepository;
import com.fraud.project.repository.UserRepository;

/**
 * Bir demo fixture'ını gerçek bir işlemmiş gibi "replay" eder: User/Device/
 * Merchant/Transaction'ı DB'ye yazar, ml-service'in /predict'ini çağırır,
 * sonucu risk_scores'a kaydeder. Fixture'ların DB'ye ÖNCEDEN seed edilmemesi
 * bilinçli bir karardı (bkz. database_decisions) — DB burada pipeline'ın
 * GİRDİSİ değil ÇIKTISI olarak dolduruluyor.
 */
@Service
public class TransactionReplayService {

    /**
     * Şu an bu akışı tetikleyen bir analist/insan kullanıcı yok (JWT/Security
     * henüz gerçek kimlik doğrulama yapmıyor, bkz. SecurityConfig) — audit_log
     * bu yüzden şimdilik hep "SYSTEM" aktörüyle yazılıyor. Analist onay/override
     * akışı eklendiğinde (örn. REVIEW'a düşen bir işlemi bir analist manuel
     * karara bağladığında) o akış kendi audit_log satırını gerçek kullanıcı
     * kimliğiyle yazacak.
     */
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final DemoFixtureLoader fixtureLoader;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final ExplanationRepository explanationRepository;
    private final AuditLogRepository auditLogRepository;
    private final MlServiceClient mlServiceClient;

    public TransactionReplayService(
        DemoFixtureLoader fixtureLoader,
        UserRepository userRepository,
        DeviceRepository deviceRepository,
        MerchantRepository merchantRepository,
        TransactionRepository transactionRepository,
        RiskScoreRepository riskScoreRepository,
        ExplanationRepository explanationRepository,
        AuditLogRepository auditLogRepository,
        MlServiceClient mlServiceClient
    ) {
        this.fixtureLoader = fixtureLoader;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.riskScoreRepository = riskScoreRepository;
        this.explanationRepository = explanationRepository;
        this.auditLogRepository = auditLogRepository;
        this.mlServiceClient = mlServiceClient;
    }

    @Transactional
    public ReplayResult replay(String scenarioId) {
        DemoTransactionFixture fixture = fixtureLoader.findByScenarioId(scenarioId)
            .orElseThrow(() -> new NoSuchElementException("Bilinmeyen demo senaryosu: " + scenarioId));

        User user = findOrCreateUser(fixture.userExternalRef());
        Device device = findOrCreateDevice(user, fixture.deviceFingerprint());
        Merchant merchant = findOrCreateMerchant(fixture.merchantName(), fixture.merchantCategory());

        Transaction transaction = transactionRepository.save(Transaction.builder()
            .user(user)
            .device(device)
            .merchant(merchant)
            .amount(fixture.amount())
            .currency(fixture.currency())
            .transactionTime(fixture.transactionTime())
            .locationCountry(fixture.locationCountry())
            .locationCity(fixture.locationCity())
            .build());

        PredictionResult prediction = mlServiceClient.predict(fixture.features());

        RiskScore riskScore = riskScoreRepository.save(RiskScore.builder()
            .transaction(transaction)
            .fraudProbability(BigDecimal.valueOf(prediction.fraudProbability()))
            .action(prediction.action())
            .modelVersion(prediction.modelVersion())
            .featureSnapshot(fixture.features())
            .build());

        ExplanationResult explanation = mlServiceClient.explain(fixture.features());
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

        return new ReplayResult(
            transaction.getId(),
            riskScore.getFraudProbability(),
            riskScore.getAction(),
            riskScore.getModelVersion()
        );
    }

    private User findOrCreateUser(String externalRef) {
        return userRepository.findByExternalRef(externalRef)
            .orElseGet(() -> userRepository.save(User.builder()
                .externalRef(externalRef)
                .build()));
    }

    private Device findOrCreateDevice(User user, String deviceFingerprint) {
        return deviceRepository.findByUserAndDeviceFingerprint(user, deviceFingerprint)
            .orElseGet(() -> deviceRepository.save(Device.builder()
                .user(user)
                .deviceFingerprint(deviceFingerprint)
                .build()));
    }

    private Merchant findOrCreateMerchant(String name, String category) {
        return merchantRepository.findByName(name)
            .orElseGet(() -> merchantRepository.save(Merchant.builder()
                .name(name)
                .category(category)
                .build()));
    }
}
