package com.fraud.project.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.AnalystReview;

public interface AnalystReviewRepository extends JpaRepository<AnalystReview, Long> {

    Optional<AnalystReview> findByTransactionId(Long transactionId);
}
