package com.fraud.project.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.fraud.project.kafka.TransactionScoringEvent;

/**
 * `transactions` topic'ini dinleyen iki bağımsız consumer group'un (fraud-backend=ML,
 * fraud-rule-engine=Rule Engine) her biri için ayrı hata yönetimi tanımlar: geçici bir
 * hatada (örn. ml-service'in kısa süreli kesintisi) 1sn aralıkla 3 deneme yapılır, hepsi
 * başarısız olursa mesaj o group'a özel bir Dead Letter Topic'e (DLT) yazılıp atlanır —
 * böylece bir consumer'ın kalıcı hatası ne diğer mesajları ne de diğer consumer'ı tıkar.
 * Consumer metodları (@Transactional) all-or-nothing olduğu için retry DB açısından
 * güvenli: yarım kalan bir yazım riski yok.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    /** İlk deneme + bu kadar retry = toplam 3 deneme. */
    private static final long RETRY_ATTEMPTS = 2L;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoringEvent> mlScoringKafkaListenerContainerFactory(
        ConsumerFactory<String, TransactionScoringEvent> consumerFactory,
        KafkaTemplate<String, TransactionScoringEvent> kafkaTemplate
    ) {
        return buildFactory(consumerFactory, kafkaTemplate, "transactions.fraud-backend.DLT");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoringEvent> ruleEngineKafkaListenerContainerFactory(
        ConsumerFactory<String, TransactionScoringEvent> consumerFactory,
        KafkaTemplate<String, TransactionScoringEvent> kafkaTemplate
    ) {
        return buildFactory(consumerFactory, kafkaTemplate, "transactions.fraud-rule-engine.DLT");
    }

    private ConcurrentKafkaListenerContainerFactory<String, TransactionScoringEvent> buildFactory(
        ConsumerFactory<String, TransactionScoringEvent> consumerFactory,
        KafkaTemplate<String, TransactionScoringEvent> kafkaTemplate,
        String deadLetterTopic
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
            (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        ConcurrentKafkaListenerContainerFactory<String, TransactionScoringEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
