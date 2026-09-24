package com.fraud.project.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Service;

import com.fraud.project.kafka.TransactionScoringEvent;

/**
 * `KafkaErrorHandlingConfig`'in Dead Letter Topic'lerine (3 başarısız denemeden
 * sonra düşen mesajlar) düşen işlemleri AYNI gerçek servis metoduyla (skorlama/
 * kural değerlendirmesi) yeniden işletir. Talep üzerine çalışan, kısa ömürlü bir
 * araç — sürekli arka planda dönen bir consumer DEĞİL (bkz. redriveMlService/
 * redriveRuleEngine, her ikisi de admin bir endpoint'ten tetikleniyor).
 *
 * Kendi kalıcı consumer group'unu kullanıyor (örn. "fraud-backend-dlq-redrive")
 * — bu sayede "hangi mesajlar zaten redrive edildi" takibini elle bir yerde
 * saklamak yerine Kafka'nın kendi offset mekanizmasına bırakıyoruz. Her mesaj
 * TEK TEK işlenip offset'i AYRI commit ediliyor; bir mesaj başarısız olursa
 * (örn. ml-service hâlâ kapalı) commit edilmeden durulur — o mesaj ve aynı
 * partition'daki sonrakiler DLT'de kalır, bir sonraki redrive çağrısında
 * tekrar denenir. Bu davranış, projenin başka yerlerindeki "sahte başarı
 * üretme, gerçek durumu yansıt" tutarlılığıyla (bkz. circuit breaker'ın
 * fallback'siz bırakılması) aynı çizgide.
 */
@Service
public class DlqRedriveService {

    private static final Logger log = LoggerFactory.getLogger(DlqRedriveService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);
    /**
     * Her redrive çağrısı YENİ bir kısa ömürlü consumer açıyor (bkz. sınıf
     * javadoc'u), yani her seferinde SIFIRDAN bir "FindCoordinator→JoinGroup→
     * SyncGroup" el sıkışması gerekiyor — bu ortamda gözlemlenen bu el
     * sıkışma gecikmesi (~20 saniyeye kadar) tek bir kısa poll'a sığmıyor. Bu
     * yüzden art arda BU KADAR boş poll gelene kadar denemeye devam ediyoruz;
     * gerçekten boş bir DLT'de bu, redrive çağrısının en kötü ihtimalle
     * ~15 saniye sürmesi anlamına gelir — bir admin işlemi için kabul edilebilir.
     */
    private static final int MAX_CONSECUTIVE_EMPTY_POLLS = 5;

    private final ConsumerFactory<String, TransactionScoringEvent> consumerFactory;
    private final TransactionScoringService transactionScoringService;
    private final RuleEngineService ruleEngineService;

    public DlqRedriveService(
        ConsumerFactory<String, TransactionScoringEvent> consumerFactory,
        TransactionScoringService transactionScoringService,
        RuleEngineService ruleEngineService
    ) {
        this.consumerFactory = consumerFactory;
        this.transactionScoringService = transactionScoringService;
        this.ruleEngineService = ruleEngineService;
    }

    public DlqRedriveResult redriveMlService() {
        return redrive(
            "transactions.fraud-backend.DLT",
            "fraud-backend-dlq-redrive",
            event -> transactionScoringService.score(event.transactionId(), event.features())
        );
    }

    public DlqRedriveResult redriveRuleEngine() {
        return redrive(
            "transactions.fraud-rule-engine.DLT",
            "fraud-rule-engine-dlq-redrive",
            event -> ruleEngineService.evaluate(event.transactionId(), event.features())
        );
    }

    private DlqRedriveResult redrive(String deadLetterTopic, String redriveGroupId, java.util.function.Consumer<TransactionScoringEvent> handler) {
        try (Consumer<String, TransactionScoringEvent> consumer = consumerFactory.createConsumer(redriveGroupId, null)) {
            consumer.subscribe(List.of(deadLetterTopic));

            int found = 0;
            int succeeded = 0;
            boolean stoppedOnFailure = false;
            int consecutiveEmptyPolls = 0;

            while (consecutiveEmptyPolls < MAX_CONSECUTIVE_EMPTY_POLLS && !stoppedOnFailure) {
                ConsumerRecords<String, TransactionScoringEvent> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    consecutiveEmptyPolls++;
                    continue;
                }
                consecutiveEmptyPolls = 0;
                found += records.count();

                for (ConsumerRecord<String, TransactionScoringEvent> record : records) {
                    try {
                        handler.accept(record.value());
                        consumer.commitSync(Map.of(
                            new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1)
                        ));
                        succeeded++;
                    } catch (Exception e) {
                        log.warn(
                            "DLQ redrive başarısız [{}], transactionId={} — bu mesaj ve aynı partition'daki sonrakiler DLT'de kalacak",
                            deadLetterTopic, record.value().transactionId(), e
                        );
                        stoppedOnFailure = true;
                        break;
                    }
                }
            }

            return new DlqRedriveResult(found, succeeded, found - succeeded);
        }
    }
}
