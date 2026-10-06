package com.waterworks.plant.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.DosingRecord;

public interface DosingRecordRepository extends JpaRepository<DosingRecord, Long> {

    /** 批次唯一槽位：同一目标工段 + 同一执行时间 */
    Optional<DosingRecord> findByTargetSectionAndExecuteAt(String targetSection, LocalDateTime executeAt);

    List<DosingRecord> findByStatusIn(List<String> statuses);

    /** 悲观写锁：确认 / 标记异常 / 复核 / 修改都先锁定本行，串行化并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DosingRecord r where r.id = :id")
    Optional<DosingRecord> findByIdForUpdate(@Param("id") Long id);
}
