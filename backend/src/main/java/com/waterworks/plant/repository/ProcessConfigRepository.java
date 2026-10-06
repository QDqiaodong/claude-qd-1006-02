package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.ProcessConfig;

public interface ProcessConfigRepository extends JpaRepository<ProcessConfig, Long> {

    List<ProcessConfig> findByItemTypeOrderById(String itemType);

    List<ProcessConfig> findByItemTypeAndEnabledTrue(String itemType);

    Optional<ProcessConfig> findByItemTypeAndItemName(String itemType, String itemName);
}
