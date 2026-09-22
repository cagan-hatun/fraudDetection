package com.fraud.project.kafka;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventProducer {

    private final KafkaTemplate<String, TransactionScoringEvent> kafkaTemplate;
    private final String topic;

    public TransactionEventProducer(
        KafkaTemplate<String, TransactionScoringEvent> kafkaTemplate,
        @Value("${kafka.topic.transactions}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Mesaj anahtarı olarak transactionId kullanılıyor — aynı transaction'a
     * ait (ileride olabilecek) birden fazla event her zaman aynı partition'a
     * gidip sırayı korusun diye.
     */
    public void publish(Long transactionId, Map<String, Object> features) {
        kafkaTemplate.send(topic, transactionId.toString(), new TransactionScoringEvent(transactionId, features));
    }
}
