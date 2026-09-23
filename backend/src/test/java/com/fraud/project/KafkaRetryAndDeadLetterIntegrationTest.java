package com.fraud.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fraud.project.mlservice.MlServiceClient;
import com.fraud.project.service.ReplayAcceptedResult;
import com.fraud.project.service.TransactionReplayService;

/**
 * Gerçek bir Kafka container'ına karşı çalışır (bkz. AbstractIntegrationTest) ve
 * KafkaErrorHandlingConfig'teki retry+DLQ mekanizmasını otomatik olarak kanıtlar —
 * bugüne kadar bu sadece elle (ml-service'i kapatıp curl ile) doğrulanmıştı.
 * MlServiceClient'i @MockitoBean ile değiştiriyoruz: bu testin amacı Kafka'nın
 * retry/DLQ davranışı, ml-service'in kendisi değil (o zaten CircuitBreaker'ın kendi
 * testinde ayrı ele alınıyor, bkz. ResilienceConfigTest) — gerçek ml-service'e
 * bağımlı olmadan deterministik bir hata üretebiliyoruz.
 */
class KafkaRetryAndDeadLetterIntegrationTest extends AbstractIntegrationTest {

    private static final String DEAD_LETTER_TOPIC = "transactions.fraud-backend.DLT";

    @Autowired
    private TransactionReplayService replayService;

    @MockitoBean
    private MlServiceClient mlServiceClient;

    @Test
    void mlServiceFailureIsRetriedThreeTimesThenDeadLettered() {
        when(mlServiceClient.predict(any())).thenThrow(new RuntimeException("ml-service kesintisi simülasyonu"));

        ReplayAcceptedResult result = replayService.replay("ordinary_small");
        String expectedIdField = "\"transactionId\":" + result.transactionId();

        // 1 ilk deneme + 2 retry = toplam 3 çağrı (bkz. KafkaErrorHandlingConfig).
        verify(mlServiceClient, timeout(10_000).atLeast(3)).predict(any());

        try (KafkaConsumer<String, String> dltConsumer = createDeadLetterTopicConsumer()) {
            dltConsumer.subscribe(Collections.singletonList(DEAD_LETTER_TOPIC));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains(expectedIdField)) {
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            });
        }
    }

    private KafkaConsumer<String, String> createDeadLetterTopicConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
