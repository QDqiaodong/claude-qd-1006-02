package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.Disposal;

public interface DisposalRepository extends JpaRepository<Disposal, Long> {

    List<Disposal> findByStatus(String status);

    List<Disposal> findBySourceNameAndStatusIn(String sourceName, List<String> statuses);

    /** 悲观写锁：处置单的状态推进 / 解除 / 冲突处理都先锁定本行，串行化并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Disposal d where d.id = :id")
    Optional<Disposal> findByIdForUpdate(@Param("id") Long id);
}
