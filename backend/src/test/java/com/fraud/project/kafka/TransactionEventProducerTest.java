package com.fraud.project.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class TransactionEventProducerTest {

    @Mock private KafkaTemplate<String, TransactionScoringEvent> kafkaTemplate;

    @Test
    void publish_sendsEventKeyedByTransactionIdToConfiguredTopic() {
        TransactionEventProducer producer = new TransactionEventProducer(kafkaTemplate, "transactions");
        Map<String, Object> features = Map.of("TransactionAmt", 100.0);

        producer.publish(42L, features);

        ArgumentCaptor<TransactionScoringEvent> captor = ArgumentCaptor.forClass(TransactionScoringEvent.class);
        verify(kafkaTemplate).send(eq("transactions"), eq("42"), captor.capture());

        assertThat(captor.getValue().transactionId()).isEqualTo(42L);
        assertThat(captor.getValue().features()).isEqualTo(features);
    }
}
