package com.fraud.project.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.Merchant;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByName(String name);
}
