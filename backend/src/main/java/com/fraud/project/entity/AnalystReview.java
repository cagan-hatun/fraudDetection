package com.fraud.project.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bir analistin REVIEW durumundaki bir işlem için verdiği karar — sistemin
 * (ML + Rule Engine) kararından TAMAMEN AYRI, ek bir bilgi katmanı.
 * risk_scores.final_action asla bununla üzerine yazılmaz.
 */
@Entity
@Table(name = "analyst_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalystReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AnalystDecision decision;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "reviewed_by", nullable = false, length = 50)
    private String reviewedBy;

    /**
     * Bilinçli olarak @CreationTimestamp DEĞİL — analist kararını
     * değiştirirse (aynı transaction için ikinci kez review gönderirse) bu
     * satır UPDATE edilir ve reviewedAt'ın da yenilenmesi gerekir; servis
     * katmanında elle set ediliyor.
     */
    @Column(name = "reviewed_at", nullable = false)
    private OffsetDateTime reviewedAt;
}
