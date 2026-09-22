package com.fraud.project.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.RuleEvaluation;

public interface RuleEvaluationRepository extends JpaRepository<RuleEvaluation, Long> {

    Optional<RuleEvaluation> findByTransactionId(Long transactionId);
}
