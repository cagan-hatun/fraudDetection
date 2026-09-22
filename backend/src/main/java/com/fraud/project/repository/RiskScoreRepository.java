package com.fraud.project.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.RiskScore;

public interface RiskScoreRepository extends JpaRepository<RiskScore, Long> {
}
