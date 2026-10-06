package com.waterworks.plant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DisposalLog;

public interface DisposalLogRepository extends JpaRepository<DisposalLog, Long> {

    List<DisposalLog> findByDisposalIdOrderById(Long disposalId);
}
