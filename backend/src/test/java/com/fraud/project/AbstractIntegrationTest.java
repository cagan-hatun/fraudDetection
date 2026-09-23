package com.fraud.project;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gerçek Postgres + Kafka container'larına karşı çalışan entegrasyon testleri için
 * ortak taban. Bugüne kadarki tüm testler (45'i) saf Mockito ile izoleydi — bu,
 * Mockito'nun asla yakalayamayacağı iki gerçek riski hedefliyor: Flyway migration'larının
 * (V1-V3) temiz bir DB'de gerçekten çalışması ve Postgres'e özgü mapping'lerin (örn.
 * RiskScore.featureSnapshot -> jsonb) doğru round-trip etmesi; Kafka tarafında da
 * KafkaErrorHandlingConfig'teki retry/DLQ mekanizmasının gerçek bir broker'a karşı
 * çalıştığının otomatik kanıtı (bugüne kadar sadece elle curl ile doğrulanmıştı).
 *
 * @ServiceConnection, container'ların bağlantı bilgilerini application.properties'teki
 * sabit localhost:5433/localhost:9092 değerlerinin ÜZERİNE yazar — testler prod
 * altyapısına dokunmadan tamamen izole çalışır. Container'lar (static) sınıflar arasında
 * paylaşılıyor: her entegrasyon testi kendi Postgres+Kafka'sını ayrı ayrı ayağa
 * kaldırmasın diye.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
}
