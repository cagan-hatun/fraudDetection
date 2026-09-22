package com.fraud.project.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.entity.RiskAction;

@ExtendWith(MockitoExtension.class)
class RuleEvaluatorTest {

    @Mock private RuleDefinitionLoader ruleDefinitionLoader;

    private static final RuleDefinition LARGE_AMOUNT = new RuleDefinition(
        "large_amount", "desc",
        "#features['TransactionAmt'] != null and #features['TransactionAmt'] > 5000",
        RiskAction.REVIEW
    );
    private static final RuleDefinition HIGH_MERCHANT_RISK = new RuleDefinition(
        "high_merchant_risk", "desc",
        "#features['merchant_risk'] != null and #features['merchant_risk'] > 0.5",
        RiskAction.BLOCK
    );

    @Test
    void evaluate_noRuleMatches_returnsApproveWithNoMatchedRules() {
        when(ruleDefinitionLoader.findAll()).thenReturn(List.of(LARGE_AMOUNT));

        RuleEvaluationOutcome outcome = new RuleEvaluator(ruleDefinitionLoader)
            .evaluate(Map.of("TransactionAmt", 50.0));

        assertThat(outcome.action()).isEqualTo(RiskAction.APPROVE);
        assertThat(outcome.matchedRuleNames()).isEmpty();
    }

    @Test
    void evaluate_oneRuleMatches_returnsItsAction() {
        when(ruleDefinitionLoader.findAll()).thenReturn(List.of(LARGE_AMOUNT));

        RuleEvaluationOutcome outcome = new RuleEvaluator(ruleDefinitionLoader)
            .evaluate(Map.of("TransactionAmt", 6000.0));

        assertThat(outcome.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(outcome.matchedRuleNames()).containsExactly("large_amount");
    }

    @Test
    void evaluate_multipleRulesMatch_mostSevereActionWins() {
        when(ruleDefinitionLoader.findAll()).thenReturn(List.of(LARGE_AMOUNT, HIGH_MERCHANT_RISK));

        RuleEvaluationOutcome outcome = new RuleEvaluator(ruleDefinitionLoader)
            .evaluate(Map.of("TransactionAmt", 6000.0, "merchant_risk", 0.9));

        assertThat(outcome.action()).isEqualTo(RiskAction.BLOCK);
        assertThat(outcome.matchedRuleNames()).containsExactlyInAnyOrder("large_amount", "high_merchant_risk");
    }

    @Test
    void evaluate_missingFeature_doesNotThrow_treatedAsNoMatch() {
        when(ruleDefinitionLoader.findAll()).thenReturn(List.of(LARGE_AMOUNT, HIGH_MERCHANT_RISK));

        // TransactionAmt ve merchant_risk hiç yok (null) — koşullardaki
        // "!= null" güvenliği sayesinde hata fırlatmamalı, hiçbir kural
        // eşleşmemeli.
        RuleEvaluationOutcome outcome = new RuleEvaluator(ruleDefinitionLoader).evaluate(Map.of());

        assertThat(outcome.action()).isEqualTo(RiskAction.APPROVE);
        assertThat(outcome.matchedRuleNames()).isEmpty();
    }
}
