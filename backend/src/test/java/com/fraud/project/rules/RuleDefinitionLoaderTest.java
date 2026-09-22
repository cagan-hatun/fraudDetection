package com.fraud.project.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fraud.project.entity.RiskAction;

class RuleDefinitionLoaderTest {

    private static final String SAMPLE_YAML = """
        rules:
          - name: sample_rule
            description: "Örnek kural"
            condition: "#features['TransactionAmt'] != null and #features['TransactionAmt'] > 100"
            action: REVIEW
        """;

    @Test
    void parse_mapsAllFields() {
        InputStream in = new ByteArrayInputStream(SAMPLE_YAML.getBytes(StandardCharsets.UTF_8));

        List<RuleDefinition> rules = RuleDefinitionLoader.parse(in);

        assertThat(rules).hasSize(1);
        RuleDefinition rule = rules.get(0);
        assertThat(rule.name()).isEqualTo("sample_rule");
        assertThat(rule.description()).isEqualTo("Örnek kural");
        assertThat(rule.condition()).contains("TransactionAmt");
        assertThat(rule.action()).isEqualTo(RiskAction.REVIEW);
    }

    @Test
    void realRulesFile_loadsThreeRules() {
        RuleDefinitionLoader loader = new RuleDefinitionLoader();

        List<RuleDefinition> rules = loader.findAll();

        assertThat(rules).hasSize(3);
        assertThat(rules).extracting(RuleDefinition::name)
            .containsExactlyInAnyOrder("large_amount", "new_device_meaningful_amount", "high_merchant_risk");
    }
}
