package com.fraud.project.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class DemoFixtureLoaderTest {

    private static final String SAMPLE_JSON = """
        [
          {
            "scenarioId": "ordinary_1",
            "label": "Sıradan bir işlem",
            "groundTruthIsFraud": false,
            "userExternalRef": "demo-user-001",
            "deviceFingerprint": "demo-device-001",
            "merchantName": "Test Merchant",
            "merchantCategory": "electronics",
            "amount": 125.50,
            "currency": "USD",
            "transactionTime": "2026-05-14T10:23:00Z",
            "locationCountry": "US",
            "locationCity": "New York",
            "features": {
              "TransactionAmt": 125.5,
              "ProductCD": "W"
            }
          }
        ]
        """;

    private static ObjectMapper testObjectMapper() {
        // DemoFixtureLoader üretimde Spring'in autoconfigure ettiği Jackson 3
        // ObjectMapper'ını kullanıyor — burada Spring context'i ayağa
        // kaldırmadan aynısını elle kuruyoruz. Jackson 3'te java.time desteği
        // databind'e gömülü, ayrı bir modül kaydına gerek yok.
        return new ObjectMapper();
    }

    @Test
    void parse_mapsAllFieldsIncludingFeatureMapAndTimestamp() {
        InputStream in = new ByteArrayInputStream(SAMPLE_JSON.getBytes(StandardCharsets.UTF_8));

        List<DemoTransactionFixture> fixtures = DemoFixtureLoader.parse(testObjectMapper(), in);

        assertThat(fixtures).hasSize(1);
        DemoTransactionFixture fixture = fixtures.get(0);
        assertThat(fixture.scenarioId()).isEqualTo("ordinary_1");
        assertThat(fixture.groundTruthIsFraud()).isFalse();
        assertThat(fixture.transactionTime()).isEqualTo(OffsetDateTime.parse("2026-05-14T10:23:00Z"));
        assertThat(fixture.features()).containsEntry("ProductCD", "W");
        assertThat(fixture.features()).containsEntry("TransactionAmt", 125.5);
    }

    @Test
    void parse_emptyArrayReturnsEmptyList() {
        InputStream in = new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8));

        List<DemoTransactionFixture> fixtures = DemoFixtureLoader.parse(testObjectMapper(), in);

        assertThat(fixtures).isEmpty();
    }

    @Test
    void realFixtureFile_loadsSixHoldoutScenariosWithFullFeatureVectors() {
        // Gerçek üretim dosyasını (fixtures/demo_transactions.json) DemoFixtureLoader
        // constructor'ı ÜZERİNDEN yüklüyoruz — Spring context'i ayağa kaldırmadan,
        // ama gerçek ClassPathResource/dosya okuma yolunu da test ederek.
        DemoFixtureLoader loader = new DemoFixtureLoader(testObjectMapper());

        assertThat(loader.findAll()).hasSize(6);
        assertThat(loader.findAll())
            .extracting(DemoTransactionFixture::scenarioId)
            .containsExactlyInAnyOrder(
                "caught_fraud", "missed_fraud", "false_positive",
                "ordinary_small", "ordinary_medium", "ordinary_large"
            );

        DemoTransactionFixture caughtFraud = loader.findByScenarioId("caught_fraud").orElseThrow();
        assertThat(caughtFraud.groundTruthIsFraud()).isTrue();
        // final_features tam 120 sütun — her fixture'ın feature map'i bu sayıyla
        // eşleşmeli, aksi halde ml-service'e eksik/fazla bir vektör giderdi.
        assertThat(caughtFraud.features()).hasSize(120);

        DemoTransactionFixture missedFraud = loader.findByScenarioId("missed_fraud").orElseThrow();
        assertThat(missedFraud.groundTruthIsFraud()).isTrue();

        DemoTransactionFixture falsePositive = loader.findByScenarioId("false_positive").orElseThrow();
        assertThat(falsePositive.groundTruthIsFraud()).isFalse();
    }
}
