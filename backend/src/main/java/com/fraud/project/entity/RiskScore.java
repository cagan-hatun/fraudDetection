package com.fraud.project.entity;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

@Entity
@Table(name = "risk_scores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "fraud_probability", nullable = false, precision = 6, scale = 5)
    private java.math.BigDecimal fraudProbability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskAction action;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    /**
     * ML + Rule Engine'in escalate-only birleştirilmiş nihai kararı — ikisi
     * de bitene kadar NULL. `action` alanı (yukarıda) SAF ML kararı olarak
     * hiç değişmeden kalır, denetim/model-izleme için.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "final_action", length = 10)
    private RiskAction finalAction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> featureSnapshot;

    /** SHAP waterfall'ının başlangıç noktası — ml-service /explain'den. */
    @Column(name = "base_value", precision = 10, scale = 6)
    private java.math.BigDecimal baseValue;

    @CreationTimestamp
    @Column(name = "scored_at", nullable = false, updatable = false)
    private OffsetDateTime scoredAt;
}
