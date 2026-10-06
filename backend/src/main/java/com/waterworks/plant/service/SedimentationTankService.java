package com.waterworks.plant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.entity.SedimentationTank;
import com.waterworks.plant.repository.SedimentationTankRepository;

/**
 * 沉淀池业务。
 *
 * <p>核心规则：① 停留时间必须大于 0，否则拒绝建档；② 状态仅允许 运行 ↔ 清洗 流转。</p>
 */
@Service
public class SedimentationTankService {

    private final SedimentationTankRepository repository;
    private final DisposalService disposalService;

    public SedimentationTankService(SedimentationTankRepository repository, DisposalService disposalService) {
        this.repository = repository;
        this.disposalService = disposalService;
    }

    public List<SedimentationTank> list(String status) {
        if (status != null && !status.isBlank()) {
            return repository.findByStatus(status);
        }
        return repository.findAll();
    }

    public SedimentationTank get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("沉淀池不存在：" + id));
    }

    @Transactional
    public SedimentationTank create(SedimentationTank input) {
        if (input.getTankCode() == null || input.getTankCode().isBlank()) {
            throw new BizException("池编号不能为空");
        }
        repository.findByTankCode(input.getTankCode().trim()).ifPresent(t -> {
            throw new BizException("池编号已存在：" + input.getTankCode());
        });
        if (input.getCapacityM3() == null || input.getCapacityM3().signum() <= 0) {
            throw new BizException("有效容积必须大于 0");
        }
        // 规则①：停留时间必须大于 0
        if (input.getRetentionMin() == null || input.getRetentionMin() <= 0) {
            throw new BizException("沉淀池停留时间必须大于 0（分钟）");
        }
        SedimentationTank tank = new SedimentationTank();
        tank.setTankCode(input.getTankCode().trim());
        tank.setCapacityM3(input.getCapacityM3());
        tank.setRetentionMin(input.getRetentionMin());
        tank.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "运行" : input.getStatus());
        tank.setNote(input.getNote());
        tank.setCreatedAt(LocalDateTime.now());
        return repository.save(tank);
    }

    @Transactional
    public SedimentationTank update(Long id, SedimentationTank input) {
        SedimentationTank tank = get(id);
        if (input.getCapacityM3() != null) {
            if (input.getCapacityM3().signum() <= 0) {
                throw new BizException("有效容积必须大于 0");
            }
            tank.setCapacityM3(input.getCapacityM3());
        }
        if (input.getRetentionMin() != null) {
            if (input.getRetentionMin() <= 0) {
                throw new BizException("沉淀池停留时间必须大于 0（分钟）");
            }
            tank.setRetentionMin(input.getRetentionMin());
        }
        if (input.getNote() != null) {
            tank.setNote(input.getNote());
        }
        return repository.save(tank);
    }

    /** 运行 ↔ 清洗 流转；转入清洗时同事务把未解除处置单里的关联标为冲突 */
    @Transactional
    public SedimentationTank transfer(Long id, String target) {
        SedimentationTank tank = get(id);
        if (!Dict.isValidTankStatus(target)) {
            throw new BizException("沉淀池状态不合法：" + target);
        }
        if (!Dict.canTransferTank(tank.getStatus(), target)) {
            throw new BizException("不允许从「" + tank.getStatus() + "」转为「" + target + "」");
        }
        tank.setStatus(target);
        SedimentationTank saved = repository.save(tank);
        // 超标处置联动：设备离开「运行」，关联标冲突（恢复运行不自动消除）
        disposalService.markEquipmentConflicts(Dict.EQUIP_TANK, id, target);
        return saved;
    }
}
