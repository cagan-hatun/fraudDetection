package com.fraud.project.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fraud.project.service.TransactionScoringService;

/**
 * `transactions` topic'ini dinler, her event için gerçek skorlamayı
 * (ml-service çağrıları + DB yazımı) `TransactionScoringService`'e
 * devrediyor. Consumer'ın kendisi iş mantığı içermiyor — sadece Kafka'dan
 * Spring servis katmanına köprü.
 */
@Component
public class TransactionScoringConsumer {

    private final TransactionScoringService transactionScoringService;

    public TransactionScoringConsumer(TransactionScoringService transactionScoringService) {
        this.transactionScoringService = transactionScoringService;
    }

    @KafkaListener(topics = "${kafka.topic.transactions}")
    public void onTransactionEvent(TransactionScoringEvent event) {
        transactionScoringService.score(event.transactionId(), event.features());
    }
}
