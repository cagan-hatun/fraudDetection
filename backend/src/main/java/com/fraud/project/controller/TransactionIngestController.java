package com.fraud.project.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fraud.project.service.ReplayAcceptedResult;
import com.fraud.project.service.SubmitTransactionRequest;
import com.fraud.project.service.TransactionReplayService;

import jakarta.validation.Valid;

/**
 * `/api/demo/**`'den bilinçli olarak AYRI: o paket 7 sabit senaryoyu
 * "replay" eder, bu ise çağıranın kendi sağladığı (fixture'a bağlı olmayan)
 * bir işlemi gerçek pipeline'a sokar — bkz. SubmitTransactionRequest'in
 * javadoc'u, neden "tam feature vektörü" ile sınırlı olduğunun gerekçesi
 * için.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionIngestController {

    private final TransactionReplayService replayService;

    public TransactionIngestController(TransactionReplayService replayService) {
        this.replayService = replayService;
    }

    /** Asenkron: transaction hemen kaydedilir, skorlama Kafka üzerinden arka planda olur. */
    @PostMapping
    public ResponseEntity<ReplayAcceptedResult> submit(@Valid @RequestBody SubmitTransactionRequest request) {
        ReplayAcceptedResult result = replayService.ingest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
