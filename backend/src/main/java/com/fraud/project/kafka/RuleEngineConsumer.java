package com.fraud.project.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fraud.project.service.RuleEngineService;

/**
 * `transactions` topic'ini `TransactionScoringConsumer`'dan TAMAMEN AYRI bir
 * consumer group'tan (`fraud-rule-engine`) dinler — bu yüzden her iki
 * consumer da her mesajın kendi kopyasını alır (Kafka'da aynı consumer
 * group'taki tüketiciler partition'ları PAYLAŞIR, ama farklı group'lar her
 * zaman TÜM mesajları görür). Mimarideki "ML Service + Rule Engine paralel
 * tüketir" ifadesinin birebir karşılığı bu.
 */
@Component
public class RuleEngineConsumer {

    private final RuleEngineService ruleEngineService;

    public RuleEngineConsumer(RuleEngineService ruleEngineService) {
        this.ruleEngineService = ruleEngineService;
    }

    @KafkaListener(
        topics = "${kafka.topic.transactions}",
        groupId = "fraud-rule-engine",
        containerFactory = "ruleEngineKafkaListenerContainerFactory"
    )
    public void onTransactionEvent(TransactionScoringEvent event) {
        ruleEngineService.evaluate(event.transactionId(), event.features());
    }
}
