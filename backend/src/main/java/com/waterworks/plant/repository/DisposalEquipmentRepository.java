package com.waterworks.plant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DisposalEquipment;

public interface DisposalEquipmentRepository extends JpaRepository<DisposalEquipment, Long> {

    List<DisposalEquipment> findByDisposalIdOrderById(Long disposalId);

    List<DisposalEquipment> findByDisposalIdIn(List<Long> disposalIds);

    /** 设备流转钩子等场景：查某台设备当前处于指定关联状态的关联记录 */
    List<DisposalEquipment> findByEquipTypeAndEquipIdAndLinkStatus(String equipType, Long equipId, String linkStatus);
}
