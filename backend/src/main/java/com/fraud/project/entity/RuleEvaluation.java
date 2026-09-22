package com.fraud.project.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Rule Engine'in ML'den TAMAMEN bağımsız kendi değerlendirmesi — risk_scores
 * ile aynı transaction'ı işaret eder ama ayrı bir Kafka consumer'ı (ayrı
 * consumer group) tarafından, paralel olarak yazılır.
 */
@Entity
@Table(name = "rule_evaluations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskAction action;

    @Column(name = "matched_rules", length = 500)
    private String matchedRules;

    @CreationTimestamp
    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private OffsetDateTime evaluatedAt;
}
