package com.waterworks.plant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.Dosing;

public interface DosingRepository extends JpaRepository<Dosing, Long> {

    List<Dosing> findByChemName(String chemName);

    List<Dosing> findByTargetTank(String targetTank);
}
