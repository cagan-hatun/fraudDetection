package com.fraud.project.kafka;

import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.service.RuleEngineService;

@ExtendWith(MockitoExtension.class)
class RuleEngineConsumerTest {

    @Mock private RuleEngineService ruleEngineService;

    @Test
    void onTransactionEvent_delegatesToRuleEngineServiceWithEventFields() {
        RuleEngineConsumer consumer = new RuleEngineConsumer(ruleEngineService);
        Map<String, Object> features = Map.of("TransactionAmt", 100.0);
        TransactionScoringEvent event = new TransactionScoringEvent(7L, features);

        consumer.onTransactionEvent(event);

        verify(ruleEngineService).evaluate(7L, features);
    }
}
