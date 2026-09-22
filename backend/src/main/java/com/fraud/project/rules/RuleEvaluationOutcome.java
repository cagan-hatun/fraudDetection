package com.fraud.project.rules;

import java.util.List;

import com.fraud.project.entity.RiskAction;

public record RuleEvaluationOutcome(RiskAction action, List<String> matchedRuleNames) {
}
