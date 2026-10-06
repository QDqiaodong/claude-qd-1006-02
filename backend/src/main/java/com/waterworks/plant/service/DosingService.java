package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.entity.Dosing;
import com.waterworks.plant.repository.DosingRepository;

/**
 * 加药业务。
 *
 * <p>核心规则：① 药剂限定为 聚合氯化铝 / 次氯酸钠；② 投加量必须大于 0；
 * ③ 目标池（工段）必须填写。</p>
 */
@Service
public class DosingService {

    private final DosingRepository repository;

    public DosingService(DosingRepository repository) {
        this.repository = repository;
    }

    public List<Dosing> list(String chemName, String targetTank) {
        if (chemName != null && !chemName.isBlank()) {
            return repository.findByChemName(chemName);
        }
        if (targetTank != null && !targetTank.isBlank()) {
            return repository.findByTargetTank(targetTank);
        }
        return repository.findAll();
    }

    public Dosing get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("加药记录不存在：" + id));
    }

    @Transactional
    public Dosing create(Dosing input) {
        // 规则①：药剂白名单
        if (!Dict.isValidChemName(input.getChemName())) {
            throw new BizException("药剂必须为 聚合氯化铝 / 次氯酸钠");
        }
        // 规则②：投加量 > 0
        if (input.getDoseMgPerL() == null || input.getDoseMgPerL().signum() <= 0) {
            throw new BizException("投加量必须大于 0（mg/L）");
        }
        // 规则③：目标池必填
        if (input.getTargetTank() == null || input.getTargetTank().isBlank()) {
            throw new BizException("目标池 / 工段不能为空");
        }
        Dosing dosing = new Dosing();
        dosing.setChemName(input.getChemName());
        dosing.setDoseMgPerL(input.getDoseMgPerL());
        dosing.setTargetTank(input.getTargetTank().trim());
        dosing.setDosedAt(input.getDosedAt() == null ? LocalDateTime.now() : input.getDosedAt());
        dosing.setNote(input.getNote());
        dosing.setCreatedAt(LocalDateTime.now());
        return repository.save(dosing);
    }

    @Transactional
    public Dosing update(Long id, Dosing input) {
        Dosing dosing = get(id);
        if (input.getChemName() != null && !input.getChemName().isBlank()) {
            if (!Dict.isValidChemName(input.getChemName())) {
                throw new BizException("药剂必须为 聚合氯化铝 / 次氯酸钠");
            }
            dosing.setChemName(input.getChemName());
        }
        if (input.getDoseMgPerL() != null) {
            if (input.getDoseMgPerL().signum() <= 0) {
                throw new BizException("投加量必须大于 0（mg/L）");
            }
            dosing.setDoseMgPerL(input.getDoseMgPerL());
        }
        if (input.getTargetTank() != null && !input.getTargetTank().isBlank()) {
            dosing.setTargetTank(input.getTargetTank().trim());
        }
        if (input.getNote() != null) {
            dosing.setNote(input.getNote());
        }
        return repository.save(dosing);
    }
}
