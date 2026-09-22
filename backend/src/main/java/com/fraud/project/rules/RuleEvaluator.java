package com.fraud.project.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import com.fraud.project.entity.RiskAction;
import com.fraud.project.service.RiskActionSeverity;

/**
 * Kuralları (SpEL koşulları) bir transaction'ın feature map'ine karşı
 * çalıştırır. Drools gibi ağır bir kural motoru yerine Spring'in kendi
 * ifade dilini (SpEL) kullanıyoruz — zaten classpath'te, yeni bağımlılık
 * gerektirmiyor, ve "configurable YAML/JSON kurallar" kararıyla tutarlı.
 */
@Component
public class RuleEvaluator {

    private final RuleDefinitionLoader ruleDefinitionLoader;
    private final ExpressionParser parser = new SpelExpressionParser();

    public RuleEvaluator(RuleDefinitionLoader ruleDefinitionLoader) {
        this.ruleDefinitionLoader = ruleDefinitionLoader;
    }

    public RuleEvaluationOutcome evaluate(Map<String, Object> features) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("features", features);

        RiskAction action = RiskAction.APPROVE;
        List<String> matchedNames = new ArrayList<>();

        for (RuleDefinition rule : ruleDefinitionLoader.findAll()) {
            Boolean matched = parser.parseExpression(rule.condition()).getValue(context, Boolean.class);
            if (Boolean.TRUE.equals(matched)) {
                matchedNames.add(rule.name());
                action = RiskActionSeverity.moreSevere(action, rule.action());
            }
        }

        return new RuleEvaluationOutcome(action, matchedNames);
    }
}
