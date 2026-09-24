package com.fraud.project.service;

import java.time.OffsetDateTime;

import com.fraud.project.entity.AnalystDecision;

public record AnalystReviewSummary(
    AnalystDecision decision,
    String note,
    String reviewedBy,
    OffsetDateTime reviewedAt
) {
}
