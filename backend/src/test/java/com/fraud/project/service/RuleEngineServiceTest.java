package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RuleEvaluation;
import com.fraud.project.entity.Transaction;
import com.fraud.project.repository.RuleEvaluationRepository;
import com.fraud.project.repository.TransactionRepository;
import com.fraud.project.rules.RuleEvaluationOutcome;
import com.fraud.project.rules.RuleEvaluator;

@ExtendWith(MockitoExtension.class)
class RuleEngineServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private RuleEvaluationRepository ruleEvaluationRepository;
    @Mock private RuleEvaluator ruleEvaluator;
    @Mock private RiskFinalizationService riskFinalizationService;

    @InjectMocks
    private RuleEngineService ruleEngineService;

    private static final Map<String, Object> FEATURES = Map.of("TransactionAmt", 6000.0);

    @Test
    void evaluate_unknownTransactionId_throws() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ruleEngineService.evaluate(99L, FEATURES))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void evaluate_savesRuleEvaluationWithMatchedRulesJoinedByComma() {
        Transaction transaction = Transaction.builder().id(1L).build();
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(transaction));
        when(ruleEvaluator.evaluate(FEATURES))
            .thenReturn(new RuleEvaluationOutcome(RiskAction.BLOCK, List.of("large_amount", "high_merchant_risk")));

        ruleEngineService.evaluate(1L, FEATURES);

        ArgumentCaptor<RuleEvaluation> captor = ArgumentCaptor.forClass(RuleEvaluation.class);
        verify(ruleEvaluationRepository).save(captor.capture());
        RuleEvaluation saved = captor.getValue();

        assertThat(saved.getTransaction()).isEqualTo(transaction);
        assertThat(saved.getAction()).isEqualTo(RiskAction.BLOCK);
        assertThat(saved.getMatchedRules()).isEqualTo("large_amount,high_merchant_risk");
    }

    @Test
    void evaluate_triggersFinalizationAttempt() {
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(Transaction.builder().id(1L).build()));
        when(ruleEvaluator.evaluate(any())).thenReturn(new RuleEvaluationOutcome(RiskAction.APPROVE, List.of()));

        ruleEngineService.evaluate(1L, FEATURES);

        verify(riskFinalizationService).tryFinalize(1L);
    }
}
