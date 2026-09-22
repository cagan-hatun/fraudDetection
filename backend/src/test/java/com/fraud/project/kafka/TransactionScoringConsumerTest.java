package com.fraud.project.kafka;

import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.service.TransactionScoringService;

@ExtendWith(MockitoExtension.class)
class TransactionScoringConsumerTest {

    @Mock private TransactionScoringService transactionScoringService;

    @Test
    void onTransactionEvent_delegatesToScoringServiceWithEventFields() {
        TransactionScoringConsumer consumer = new TransactionScoringConsumer(transactionScoringService);
        Map<String, Object> features = Map.of("TransactionAmt", 100.0);
        TransactionScoringEvent event = new TransactionScoringEvent(7L, features);

        consumer.onTransactionEvent(event);

        verify(transactionScoringService).score(7L, features);
    }
}
