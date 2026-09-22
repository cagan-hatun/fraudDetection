package com.fraud.project.rules;

import com.fraud.project.entity.RiskAction;

public record RuleDefinition(String name, String description, String condition, RiskAction action) {
}
