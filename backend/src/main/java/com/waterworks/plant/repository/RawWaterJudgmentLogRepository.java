package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.RawWaterJudgmentLog;

public interface RawWaterJudgmentLogRepository extends JpaRepository<RawWaterJudgmentLog, Long> {

    /** 某条原水记录的全部裁决日志，按入库顺序（每次裁决独立追溯） */
    List<RawWaterJudgmentLog> findByRawWaterIdOrderById(Long rawWaterId);

    /** 按客户端请求标识查重：同一请求重放时复用首次裁决结果 */
    Optional<RawWaterJudgmentLog> findByRequestId(String requestId);
}
