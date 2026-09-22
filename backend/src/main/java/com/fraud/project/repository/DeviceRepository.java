package com.fraud.project.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fraud.project.entity.Device;
import com.fraud.project.entity.User;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByUserAndDeviceFingerprint(User user, String deviceFingerprint);
}
