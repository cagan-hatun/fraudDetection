package com.fraud.project.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.RuleEvaluation;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.RuleEvaluationRepository;

/**
 * ML consumer'ı (TransactionScoringService) ve Rule Engine consumer'ı
 * (RuleEngineService) birbirinden TAMAMEN BAĞIMSIZ, paralel çalışıyor —
 * hangisi biterse bitsin bu metodu çağırıyor. İkisi de yazmışsa escalate-only
 * (daha ağır olan kazanır) nihai kararı hesaplayıp risk_scores.final_action'a
 * yazıyor. `finalAction != null` kontrolü hem "henüz ikisi de bitmedi" hem de
 * "zaten finalize edilmiş" (idempotency — iki taraf da neredeyse aynı anda
 * çağırırsa) durumlarını tek seferde ele alıyor.
 */
@Service
public class RiskFinalizationService {

    private static final String RULE_ENGINE_ACTOR = "RULE_ENGINE";

    private final RiskScoreRepository riskScoreRepository;
    private final RuleEvaluationRepository ruleEvaluationRepository;
    private final AuditLogRepository auditLogRepository;

    public RiskFinalizationService(
        RiskScoreRepository riskScoreRepository,
        RuleEvaluationRepository ruleEvaluationRepository,
        AuditLogRepository auditLogRepository
    ) {
        this.riskScoreRepository = riskScoreRepository;
        this.ruleEvaluationRepository = ruleEvaluationRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void tryFinalize(Long transactionId) {
        Optional<RiskScore> riskScoreOpt = riskScoreRepository.findByTransactionId(transactionId);
        Optional<RuleEvaluation> ruleEvaluationOpt = ruleEvaluationRepository.findByTransactionId(transactionId);

        if (riskScoreOpt.isEmpty() || ruleEvaluationOpt.isEmpty()) {
            return;
        }

        RiskScore riskScore = riskScoreOpt.get();
        if (riskScore.getFinalAction() != null) {
            return;
        }

        RiskAction mlAction = riskScore.getAction();
        RiskAction ruleAction = ruleEvaluationOpt.get().getAction();
        RiskAction finalAction = RiskActionSeverity.moreSevere(mlAction, ruleAction);

        riskScore.setFinalAction(finalAction);
        riskScoreRepository.save(riskScore);

        // Rule Engine, ML'in kararını GERÇEKTEN değiştirdiyse (escalate
        // ettiyse) bu ayrı bir denetim olayı — audit_log'a ikinci bir satır
        // düşüyoruz. Escalation yoksa (ikisi zaten aynıysa) gürültü
        // eklemiyoruz.
        if (finalAction != mlAction) {
            auditLogRepository.save(AuditLog.builder()
                .transaction(riskScore.getTransaction())
                .riskScore(riskScore)
                .actor(RULE_ENGINE_ACTOR)
                .actionTaken(finalAction.name())
                .build());
        }
    }
}
