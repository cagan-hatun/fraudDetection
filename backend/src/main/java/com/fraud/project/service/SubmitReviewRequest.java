package com.fraud.project.service;

import com.fraud.project.entity.AnalystDecision;

public record SubmitReviewRequest(
    AnalystDecision decision,
    String note
) {
}
