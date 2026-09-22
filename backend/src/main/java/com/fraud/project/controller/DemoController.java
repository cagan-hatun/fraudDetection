package com.fraud.project.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fraud.project.fixture.DemoFixtureLoader;
import com.fraud.project.service.ReplayAcceptedResult;
import com.fraud.project.service.TransactionReplayService;
import com.fraud.project.service.TransactionScoringService;
import com.fraud.project.service.TransactionStatusResult;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoFixtureLoader fixtureLoader;
    private final TransactionReplayService replayService;
    private final TransactionScoringService transactionScoringService;

    public DemoController(
        DemoFixtureLoader fixtureLoader,
        TransactionReplayService replayService,
        TransactionScoringService transactionScoringService
    ) {
        this.fixtureLoader = fixtureLoader;
        this.replayService = replayService;
        this.transactionScoringService = transactionScoringService;
    }

    @GetMapping("/scenarios")
    public List<ScenarioSummary> listScenarios() {
        return fixtureLoader.findAll().stream()
            .map(f -> new ScenarioSummary(f.scenarioId(), f.label(), f.groundTruthIsFraud()))
            .toList();
    }

    /** Asenkron: transaction hemen kaydedilir, skorlama Kafka üzerinden arka planda olur. */
    @PostMapping("/replay/{scenarioId}")
    public ResponseEntity<ReplayAcceptedResult> replay(@PathVariable String scenarioId) {
        ReplayAcceptedResult result = replayService.replay(scenarioId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping("/transactions/{id}")
    public TransactionStatusResult getTransactionStatus(@PathVariable Long id) {
        return transactionScoringService.getStatus(id);
    }
}
