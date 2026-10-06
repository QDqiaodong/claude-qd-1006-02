package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DisposalDosing;

public interface DisposalDosingRepository extends JpaRepository<DisposalDosing, Long> {

    List<DisposalDosing> findByDisposalIdOrderById(Long disposalId);

    List<DisposalDosing> findByDisposalIdIn(List<Long> disposalIds);

    /** 幂等键：同一处置单内「同药剂 + 同目标工段」的加药动作只应有一条 */
    Optional<DisposalDosing> findByDisposalIdAndChemNameAndTargetTank(Long disposalId, String chemName, String targetTank);
}
