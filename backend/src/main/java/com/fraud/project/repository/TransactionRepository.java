package com.fraud.project.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
}
