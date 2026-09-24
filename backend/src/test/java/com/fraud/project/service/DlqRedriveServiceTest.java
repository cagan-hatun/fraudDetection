package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.ConsumerFactory;

import com.fraud.project.kafka.TransactionScoringEvent;

@ExtendWith(MockitoExtension.class)
class DlqRedriveServiceTest {

    private static final String ML_DLT = "transactions.fraud-backend.DLT";

    @Mock private ConsumerFactory<String, TransactionScoringEvent> consumerFactory;
    @Mock private Consumer<String, TransactionScoringEvent> kafkaConsumer;
    @Mock private TransactionScoringService transactionScoringService;
    @Mock private RuleEngineService ruleEngineService;

    private DlqRedriveService dlqRedriveService;

    private void setUp() {
        dlqRedriveService = new DlqRedriveService(consumerFactory, transactionScoringService, ruleEngineService);
        when(consumerFactory.createConsumer("fraud-backend-dlq-redrive", null)).thenReturn(kafkaConsumer);
    }

    private ConsumerRecord<String, TransactionScoringEvent> record(long offset, long transactionId) {
        return new ConsumerRecord<>(ML_DLT, 0, offset, "key",
            new TransactionScoringEvent(transactionId, Map.of("TransactionAmt", 100.0)));
    }

    @Test
    void redriveMlService_noMessages_returnsAllZeros() {
        setUp();
        when(kafkaConsumer.poll(any())).thenReturn(ConsumerRecords.empty());

        DlqRedriveResult result = dlqRedriveService.redriveMlService();

        assertThat(result).isEqualTo(new DlqRedriveResult(0, 0, 0));
        verify(transactionScoringService, never()).score(any(), anyMap());
    }

    @Test
    void redriveMlService_allSucceed_commitsEachOffsetAndCallsScoreForEach() {
        setUp();
        var r1 = record(5L, 101L);
        var r2 = record(6L, 102L);
        ConsumerRecords<String, TransactionScoringEvent> records =
            new ConsumerRecords<>(Map.of(new TopicPartition(ML_DLT, 0), List.of(r1, r2)), Map.of());
        // İlk poll backlog'u getirir, sonraki İKİ boş poll (MAX_CONSECUTIVE_EMPTY_POLLS)
        // döngünün "backlog bitti" diyip durmasını sağlar.
        when(kafkaConsumer.poll(any())).thenReturn(records, ConsumerRecords.empty(), ConsumerRecords.empty());

        DlqRedriveResult result = dlqRedriveService.redriveMlService();

        assertThat(result).isEqualTo(new DlqRedriveResult(2, 2, 0));
        verify(transactionScoringService).score(101L, r1.value().features());
        verify(transactionScoringService).score(102L, r2.value().features());
        verify(kafkaConsumer).commitSync(Map.of(new TopicPartition(ML_DLT, 0), new org.apache.kafka.clients.consumer.OffsetAndMetadata(6L)));
        verify(kafkaConsumer).commitSync(Map.of(new TopicPartition(ML_DLT, 0), new org.apache.kafka.clients.consumer.OffsetAndMetadata(7L)));
    }

    @Test
    void redriveMlService_firstMessageFails_stopsAndLeavesRestUncommitted() {
        setUp();
        var r1 = record(5L, 101L);
        var r2 = record(6L, 102L);
        ConsumerRecords<String, TransactionScoringEvent> records =
            new ConsumerRecords<>(Map.of(new TopicPartition(ML_DLT, 0), List.of(r1, r2)), Map.of());
        when(kafkaConsumer.poll(any())).thenReturn(records);
        doThrow(new RuntimeException("ml-service hâlâ kapalı")).when(transactionScoringService).score(101L, r1.value().features());

        DlqRedriveResult result = dlqRedriveService.redriveMlService();

        // found=2 (poll'da gelen toplam), succeeded=0, failed=2 (başarısız olan +
        // ardından hiç denenmeyen ikinci mesaj da "failed" sayılır — DLT'de kalıyor).
        assertThat(result).isEqualTo(new DlqRedriveResult(2, 0, 2));
        verify(transactionScoringService, never()).score(102L, r2.value().features());
        verify(kafkaConsumer, never()).commitSync(anyMap());
    }

    @Test
    void redriveRuleEngine_usesItsOwnDedicatedRedriveGroupAndDlt() {
        dlqRedriveService = new DlqRedriveService(consumerFactory, transactionScoringService, ruleEngineService);
        when(consumerFactory.createConsumer("fraud-rule-engine-dlq-redrive", null)).thenReturn(kafkaConsumer);
        var r1 = new ConsumerRecord<>("transactions.fraud-rule-engine.DLT", 0, 3L, "key",
            new TransactionScoringEvent(201L, Map.of("TransactionAmt", 50.0)));
        ConsumerRecords<String, TransactionScoringEvent> records = new ConsumerRecords<>(
            Map.of(new TopicPartition("transactions.fraud-rule-engine.DLT", 0), List.of(r1)), Map.of());
        when(kafkaConsumer.poll(any())).thenReturn(records, ConsumerRecords.empty(), ConsumerRecords.empty());

        DlqRedriveResult result = dlqRedriveService.redriveRuleEngine();

        assertThat(result).isEqualTo(new DlqRedriveResult(1, 1, 0));
        verify(ruleEngineService).evaluate(201L, r1.value().features());
        verify(consumerFactory).createConsumer("fraud-rule-engine-dlq-redrive", null);
    }
}
