package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.RawWaterReclassifyLog;

public interface RawWaterReclassifyLogRepository extends JpaRepository<RawWaterReclassifyLog, Long> {

    /** 某样本的完整裁决链，按发生顺序排列，每次裁决独立可追溯 */
    List<RawWaterReclassifyLog> findByRawWaterIdOrderById(Long rawWaterId);

    /** 幂等槽：按请求令牌回查已落库的裁决，命中则安全复用同一结果 */
    Optional<RawWaterReclassifyLog> findByRequestId(String requestId);

    long countByRawWaterId(Long rawWaterId);
}
