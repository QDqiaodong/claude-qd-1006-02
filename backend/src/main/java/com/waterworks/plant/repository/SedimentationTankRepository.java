package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.SedimentationTank;

public interface SedimentationTankRepository extends JpaRepository<SedimentationTank, Long> {

    Optional<SedimentationTank> findByTankCode(String tankCode);

    List<SedimentationTank> findByStatus(String status);
}
