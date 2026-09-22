package com.fraud.project.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
