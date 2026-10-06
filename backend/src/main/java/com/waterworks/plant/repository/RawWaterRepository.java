package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.RawWater;

public interface RawWaterRepository extends JpaRepository<RawWater, Long> {

    List<RawWater> findByStatus(String status);

    List<RawWater> findBySourceName(String sourceName);

    /** 悲观写锁：人工重判先锁定样本行，并发裁决串行化，后到者必须基于先到者的结论判断 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RawWater r where r.id = :id")
    Optional<RawWater> findByIdForUpdate(@Param("id") Long id);
}
