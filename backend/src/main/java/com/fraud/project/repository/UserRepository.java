package com.fraud.project.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByExternalRef(String externalRef);
}
