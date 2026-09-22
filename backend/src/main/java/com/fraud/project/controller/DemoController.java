package com.fraud.project.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fraud.project.fixture.DemoFixtureLoader;
import com.fraud.project.service.ReplayResult;
import com.fraud.project.service.TransactionReplayService;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoFixtureLoader fixtureLoader;
    private final TransactionReplayService replayService;

    public DemoController(DemoFixtureLoader fixtureLoader, TransactionReplayService replayService) {
        this.fixtureLoader = fixtureLoader;
        this.replayService = replayService;
    }

    @GetMapping("/scenarios")
    public List<ScenarioSummary> listScenarios() {
        return fixtureLoader.findAll().stream()
            .map(f -> new ScenarioSummary(f.scenarioId(), f.label(), f.groundTruthIsFraud()))
            .toList();
    }

    @PostMapping("/replay/{scenarioId}")
    public ReplayResult replay(@PathVariable String scenarioId) {
        return replayService.replay(scenarioId);
    }
}
