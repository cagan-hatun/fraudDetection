package com.fraud.project.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fraud.project.service.DlqRedriveResult;
import com.fraud.project.service.DlqRedriveService;

/**
 * `/api/admin/**` — sadece ADMIN rolü (bkz. SecurityConfig). Bir analistin
 * değil, bir operasyon/altyapı sorumlusunun kullanacağı bir araç.
 */
@RestController
@RequestMapping("/api/admin/dlq")
public class DlqAdminController {

    private final DlqRedriveService dlqRedriveService;

    public DlqAdminController(DlqRedriveService dlqRedriveService) {
        this.dlqRedriveService = dlqRedriveService;
    }

    @PostMapping("/ml-service/redrive")
    public DlqRedriveResult redriveMlService() {
        return dlqRedriveService.redriveMlService();
    }

    @PostMapping("/rule-engine/redrive")
    public DlqRedriveResult redriveRuleEngine() {
        return dlqRedriveService.redriveRuleEngine();
    }
}
