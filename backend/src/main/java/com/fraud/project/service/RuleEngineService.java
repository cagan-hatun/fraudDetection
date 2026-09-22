package com.fraud.project.service;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fraud.project.entity.RuleEvaluation;
import com.fraud.project.entity.Transaction;
import com.fraud.project.repository.RuleEvaluationRepository;
import com.fraud.project.repository.TransactionRepository;
import com.fraud.project.rules.RuleEvaluationOutcome;
import com.fraud.project.rules.RuleEvaluator;

/**
 * `RuleEngineConsumer`'ın (Kafka'daki `transactions` topic'ini ML'den TAMAMEN
 * AYRI bir consumer group'tan dinleyen) çağırdığı iş mantığı. ML tarafını
 * hiç bilmiyor/beklemiyor — kendi değerlendirmesini yazıp finalize adımını
 * tetikliyor, o adım ikisinin de bitip bitmediğine bakıyor.
 */
@Service
public class RuleEngineService {

    private final TransactionRepository transactionRepository;
    private final RuleEvaluationRepository ruleEvaluationRepository;
    private final RuleEvaluator ruleEvaluator;
    private final RiskFinalizationService riskFinalizationService;

    public RuleEngineService(
        TransactionRepository transactionRepository,
        RuleEvaluationRepository ruleEvaluationRepository,
        RuleEvaluator ruleEvaluator,
        RiskFinalizationService riskFinalizationService
    ) {
        this.transactionRepository = transactionRepository;
        this.ruleEvaluationRepository = ruleEvaluationRepository;
        this.ruleEvaluator = ruleEvaluator;
        this.riskFinalizationService = riskFinalizationService;
    }

    @Transactional
    public void evaluate(Long transactionId, Map<String, Object> features) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new NoSuchElementException("Bilinmeyen transaction: " + transactionId));

        RuleEvaluationOutcome outcome = ruleEvaluator.evaluate(features);

        ruleEvaluationRepository.save(RuleEvaluation.builder()
            .transaction(transaction)
            .action(outcome.action())
            .matchedRules(String.join(",", outcome.matchedRuleNames()))
            .build());

        riskFinalizationService.tryFinalize(transactionId);
    }
}
