package com.waterworks.plant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DosingRecordLog;

public interface DosingRecordLogRepository extends JpaRepository<DosingRecordLog, Long> {

    List<DosingRecordLog> findByRecordIdOrderById(Long recordId);
}
