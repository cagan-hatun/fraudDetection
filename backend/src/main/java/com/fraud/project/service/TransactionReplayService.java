package com.fraud.project.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fraud.project.entity.Device;
import com.fraud.project.entity.Merchant;
import com.fraud.project.entity.Transaction;
import com.fraud.project.entity.User;
import com.fraud.project.fixture.DemoFixtureLoader;
import com.fraud.project.fixture.DemoTransactionFixture;
import com.fraud.project.kafka.TransactionEventProducer;
import com.fraud.project.repository.DeviceRepository;
import com.fraud.project.repository.MerchantRepository;
import com.fraud.project.repository.TransactionRepository;
import com.fraud.project.repository.UserRepository;

/**
 * Bir demo fixture'ını gerçek bir işlemmiş gibi "replay" eder — ÜRETİCİ taraf:
 * User/Device/Merchant/Transaction'ı DB'ye yazar, sonra "bu transaction'ı
 * skorla" event'ini Kafka'ya basar ve HEMEN döner (PENDING). Asıl skorlama
 * (ml-service çağrısı + risk_scores/explanations/audit_log yazımı) artık
 * `TransactionScoringService`'te, Kafka consumer'ı tarafından tetikleniyor —
 * bkz. `kafka.TransactionScoringConsumer`.
 *
 * Fixture'ların DB'ye ÖNCEDEN seed edilmemesi bilinçli bir karardı (bkz.
 * database_decisions) — DB burada pipeline'ın GİRDİSİ değil ÇIKTISI olarak
 * dolduruluyor.
 */
@Service
public class TransactionReplayService {

    private final DemoFixtureLoader fixtureLoader;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventProducer transactionEventProducer;

    public TransactionReplayService(
        DemoFixtureLoader fixtureLoader,
        UserRepository userRepository,
        DeviceRepository deviceRepository,
        MerchantRepository merchantRepository,
        TransactionRepository transactionRepository,
        TransactionEventProducer transactionEventProducer
    ) {
        this.fixtureLoader = fixtureLoader;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.transactionEventProducer = transactionEventProducer;
    }

    @Transactional
    public ReplayAcceptedResult replay(String scenarioId) {
        DemoTransactionFixture fixture = fixtureLoader.findByScenarioId(scenarioId)
            .orElseThrow(() -> new NoSuchElementException("Bilinmeyen demo senaryosu: " + scenarioId));

        return accept(
            fixture.userExternalRef(),
            fixture.deviceFingerprint(),
            fixture.merchantName(),
            fixture.merchantCategory(),
            fixture.amount(),
            fixture.currency(),
            fixture.transactionTime(),
            fixture.locationCountry(),
            fixture.locationCity(),
            fixture.features()
        );
    }

    /**
     * `replay`'den farklı olarak sabit bir fixture'a değil, çağıranın kendi
     * gönderdiği tam feature vektörüne dayanır (bkz. SubmitTransactionRequest'in
     * javadoc'u) — ama sonrasında AYNI gerçek pipeline'dan (Kafka→ML+Rules→
     * escalate-only merge) geçer, demo'ya özel bir kısayol YOKTUR.
     */
    @Transactional
    public ReplayAcceptedResult ingest(SubmitTransactionRequest request) {
        OffsetDateTime transactionTime = request.transactionTime() != null
            ? request.transactionTime()
            : OffsetDateTime.now();

        return accept(
            request.userExternalRef(),
            request.deviceFingerprint(),
            request.merchantName(),
            request.merchantCategory(),
            request.amount(),
            request.currency(),
            transactionTime,
            request.locationCountry(),
            request.locationCity(),
            request.features()
        );
    }

    private ReplayAcceptedResult accept(
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
        User user = findOrCreateUser(userExternalRef);
        Device device = findOrCreateDevice(user, deviceFingerprint);
        Merchant merchant = findOrCreateMerchant(merchantName, merchantCategory);

        Transaction transaction = transactionRepository.save(Transaction.builder()
            .user(user)
            .device(device)
            .merchant(merchant)
            .amount(amount)
            .currency(currency)
            .transactionTime(transactionTime)
            .locationCountry(locationCountry)
            .locationCity(locationCity)
            .build());

        // KRİTİK: publish'i doğrudan burada YAPMIYORUZ. Bu metod hâlâ
        // @Transactional içindeyken Kafka'ya basarsak, consumer (aynı
        // JVM'de, çok hızlı) event'i işleyip transactionRepository.findById
        // çağırdığında bu DB transaction'ı HENÜZ COMMIT OLMAMIŞ olabilir —
        // consumer "transaction bulunamadı" hatasıyla patlar (klasik
        // "dual write" / transactional outbox sorunu). Çözüm: publish'i
        // TransactionSynchronizationManager ile transaction COMMIT OLDUKTAN
        // SONRA çalışacak şekilde erteliyoruz.
        Long transactionId = transaction.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                transactionEventProducer.publish(transactionId, features);
            }
        });

        return new ReplayAcceptedResult(transactionId, TransactionStatus.PENDING);
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
