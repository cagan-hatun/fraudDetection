package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
class TransactionReplayServiceTest {

    @Mock private DemoFixtureLoader fixtureLoader;
    @Mock private UserRepository userRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private MerchantRepository merchantRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionEventProducer transactionEventProducer;

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
        // replay() bir gerçek Spring @Transactional proxy'si olmadan
        // çağrılıyor — TransactionSynchronizationManager.registerSynchronization
        // aktif bir senkronizasyon olmadan çağrılırsa IllegalStateException
        // fırlatır, o yüzden burada elle başlatıyoruz (gerçek transaction
        // commit'ini simüle etmek için).
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void simulateCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    @Test
    void replay_unknownScenarioId_throws() {
        when(fixtureLoader.findByScenarioId("no_such_scenario")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replayService.replay("no_such_scenario"))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void replay_newUserDeviceMerchant_createsThem() {
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

        when(transactionRepository.save(any())).thenReturn(Transaction.builder().id(42L).build());

        ReplayAcceptedResult result = replayService.replay("caught_fraud");

        verify(userRepository).save(any());
        verify(deviceRepository).save(any());
        verify(merchantRepository).save(any());

        assertThat(result.transactionId()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(TransactionStatus.PENDING);
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

        replayService.replay("caught_fraud");

        verify(userRepository, never()).save(any());
        verify(deviceRepository, never()).save(any());
        verify(merchantRepository, never()).save(any());
    }

    @Test
    void replay_doesNotPublishUntilTransactionCommits() {
        when(fixtureLoader.findByScenarioId("caught_fraud")).thenReturn(Optional.of(fixture));
        when(userRepository.findByExternalRef(any())).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepository.findByUserAndDeviceFingerprint(any(), any()))
            .thenReturn(Optional.of(Device.builder().id(1L).build()));
        when(merchantRepository.findByName(any())).thenReturn(Optional.of(Merchant.builder().id(1L).build()));
        when(transactionRepository.save(any())).thenReturn(Transaction.builder().id(10L).build());

        replayService.replay("caught_fraud");

        // Kritik doğrulama: metod dönmüş olsa bile, DB transaction'ı henüz
        // "commit olmadığı" için Kafka'ya HİÇBİR ŞEY basılmamış olmalı —
        // aksi halde consumer, henüz commit olmamış bir transaction'ı DB'de
        // bulamaz (dual-write / transactional outbox sorunu).
        verify(transactionEventProducer, never()).publish(any(), any());

        simulateCommit();

        verify(transactionEventProducer).publish(10L, fixture.features());
    }
}
