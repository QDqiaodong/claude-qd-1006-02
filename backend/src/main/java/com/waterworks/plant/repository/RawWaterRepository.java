package com.waterworks.plant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.RawWater;

public interface RawWaterRepository extends JpaRepository<RawWater, Long> {

    List<RawWater> findByStatus(String status);

    List<RawWater> findBySourceName(String sourceName);
}
