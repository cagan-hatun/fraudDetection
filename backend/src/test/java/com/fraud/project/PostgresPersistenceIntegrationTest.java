package com.fraud.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.Transaction;
import com.fraud.project.fixture.DemoFixtureLoader;
import com.fraud.project.fixture.DemoTransactionFixture;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.TransactionRepository;
import com.fraud.project.service.ReplayAcceptedResult;
import com.fraud.project.service.TransactionReplayService;

/**
 * Gerçek bir Postgres container'ına karşı çalışır (bkz. AbstractIntegrationTest).
 * İki riski hedefliyor: Flyway migration'larının (V1-V3) gerçekten temiz bir DB'de
 * çalışması (replay/save başarısız olursa şema bozuktur) ve RiskScore.featureSnapshot
 * gibi jsonb alanların Hibernate ile doğru round-trip etmesi — bu ikisi de mock'lu
 * testlerde asla test edilemeyen, gerçek Postgres gerektiren riskler.
 */
class PostgresPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TransactionReplayService replayService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RiskScoreRepository riskScoreRepository;

    @Autowired
    private DemoFixtureLoader fixtureLoader;

    @Test
    void replayPersistsTransactionAndFeatureSnapshotRoundTripsThroughJsonb() {
        DemoTransactionFixture fixture = fixtureLoader.findByScenarioId("caught_fraud").orElseThrow();

        ReplayAcceptedResult result = replayService.replay("caught_fraud");

        Transaction transaction = transactionRepository.findById(result.transactionId()).orElseThrow();
        assertThat(transaction.getAmount()).isEqualByComparingTo(fixture.amount());
        assertThat(transaction.getCurrency()).isEqualTo(fixture.currency());

        RiskScore saved = riskScoreRepository.save(RiskScore.builder()
            .transaction(transaction)
            .fraudProbability(new BigDecimal("0.99500"))
            .action(RiskAction.BLOCK)
            .modelVersion("test-model-v1")
            .featureSnapshot(fixture.features())
            .build());

        RiskScore reloaded = riskScoreRepository.findById(saved.getId()).orElseThrow();
        Map<String, Object> roundTripped = reloaded.getFeatureSnapshot();

        assertThat(roundTripped).hasSize(fixture.features().size());
        assertThat(roundTripped.get("ProductCD")).isEqualTo(fixture.features().get("ProductCD"));
        assertThat(((Number) roundTripped.get("TransactionAmt")).doubleValue())
            .isEqualTo(((Number) fixture.features().get("TransactionAmt")).doubleValue());
    }
}
