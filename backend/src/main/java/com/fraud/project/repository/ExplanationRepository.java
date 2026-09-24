package com.fraud.project.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fraud.project.entity.Explanation;

public interface ExplanationRepository extends JpaRepository<Explanation, Long> {

    /**
     * Mutlak SHAP değerine göre büyükten küçüğe — "en etkili özellikler
     * önce" göstermek için. Method-name türetmesi ABS() sıralamasını
     * ifade edemediği için elle JPQL.
     */
    @Query("SELECT e FROM Explanation e WHERE e.riskScore.id = :riskScoreId ORDER BY ABS(e.shapValue) DESC")
    List<Explanation> findByRiskScoreIdOrderByAbsShapValueDesc(@Param("riskScoreId") Long riskScoreId);
}
