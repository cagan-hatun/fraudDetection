package com.fraud.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fraud.project.entity.AuditLog;
import com.fraud.project.entity.RiskAction;
import com.fraud.project.entity.RiskScore;
import com.fraud.project.entity.RuleEvaluation;
import com.fraud.project.entity.Transaction;
import com.fraud.project.repository.AuditLogRepository;
import com.fraud.project.repository.RiskScoreRepository;
import com.fraud.project.repository.RuleEvaluationRepository;

@ExtendWith(MockitoExtension.class)
class RiskFinalizationServiceTest {

    @Mock private RiskScoreRepository riskScoreRepository;
    @Mock private RuleEvaluationRepository ruleEvaluationRepository;
    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks
    private RiskFinalizationService riskFinalizationService;

    @Test
    void tryFinalize_onlyRiskScoreExists_doesNothing() {
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.of(RiskScore.builder().build()));
        when(ruleEvaluationRepository.findByTransactionId(1L)).thenReturn(Optional.empty());

        riskFinalizationService.tryFinalize(1L);

        verify(riskScoreRepository, never()).save(any());
    }

    @Test
    void tryFinalize_onlyRuleEvaluationExists_doesNothing() {
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.empty());
        when(ruleEvaluationRepository.findByTransactionId(1L))
            .thenReturn(Optional.of(RuleEvaluation.builder().action(RiskAction.REVIEW).build()));

        riskFinalizationService.tryFinalize(1L);

        verify(riskScoreRepository, never()).save(any());
    }

    @Test
    void tryFinalize_bothExist_ruleEscalatesAboveMl_finalActionIsTheMoreSevereOne() {
        Transaction transaction = Transaction.builder().id(1L).build();
        RiskScore riskScore = RiskScore.builder().action(RiskAction.APPROVE).transaction(transaction).build();
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.of(riskScore));
        when(ruleEvaluationRepository.findByTransactionId(1L))
            .thenReturn(Optional.of(RuleEvaluation.builder().action(RiskAction.REVIEW).build()));

        riskFinalizationService.tryFinalize(1L);

        ArgumentCaptor<RiskScore> captor = ArgumentCaptor.forClass(RiskScore.class);
        verify(riskScoreRepository).save(captor.capture());
        assertThat(captor.getValue().getFinalAction()).isEqualTo(RiskAction.REVIEW);
    }

    @Test
    void tryFinalize_mlIsMoreSevereThanRule_finalActionIsMlAction() {
        RiskScore riskScore = RiskScore.builder().action(RiskAction.BLOCK).transaction(Transaction.builder().build()).build();
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.of(riskScore));
        when(ruleEvaluationRepository.findByTransactionId(1L))
            .thenReturn(Optional.of(RuleEvaluation.builder().action(RiskAction.APPROVE).build()));

        riskFinalizationService.tryFinalize(1L);

        assertThat(riskScore.getFinalAction()).isEqualTo(RiskAction.BLOCK);
    }

    @Test
    void tryFinalize_alreadyFinalized_isIdempotent_doesNotSaveAgain() {
        RiskScore riskScore = RiskScore.builder().action(RiskAction.APPROVE).finalAction(RiskAction.REVIEW).build();
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.of(riskScore));
        when(ruleEvaluationRepository.findByTransactionId(1L))
            .thenReturn(Optional.of(RuleEvaluation.builder().action(RiskAction.REVIEW).build()));

        riskFinalizationService.tryFinalize(1L);

        verify(riskScoreRepository, never()).save(any());
    }

    @Test
    void tryFinalize_ruleEscalates_writesAuditLogEntryForTheEscalation() {
        Transaction transaction = Transaction.builder().id(1L).build();
        RiskScore riskScore = RiskScore.builder().action(RiskAction.APPROVE).transaction(transaction).build();
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.of(riskScore));
        when(ruleEvaluationRepository.findByTransactionId(1L))
            .thenReturn(Optional.of(RuleEvaluation.builder().action(RiskAction.BLOCK).build()));

        riskFinalizationService.tryFinalize(1L);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("RULE_ENGINE");
        assertThat(captor.getValue().getActionTaken()).isEqualTo("BLOCK");
        assertThat(captor.getValue().getTransaction()).isEqualTo(transaction);
    }

    @Test
    void tryFinalize_noEscalation_mlAndRuleAgree_doesNotWriteExtraAuditLogEntry() {
        RiskScore riskScore = RiskScore.builder().action(RiskAction.APPROVE).transaction(Transaction.builder().build()).build();
        when(riskScoreRepository.findByTransactionId(1L)).thenReturn(Optional.of(riskScore));
        when(ruleEvaluationRepository.findByTransactionId(1L))
            .thenReturn(Optional.of(RuleEvaluation.builder().action(RiskAction.APPROVE).build()));

        riskFinalizationService.tryFinalize(1L);

        verify(auditLogRepository, never()).save(any());
    }
}
