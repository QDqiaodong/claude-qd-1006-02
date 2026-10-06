package com.waterworks.plant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.entity.Filter;
import com.waterworks.plant.repository.FilterRepository;

/**
 * 滤池业务。
 *
 * <p>核心规则：① 滤池类型必须为 砂滤 / 炭滤，流量 > 0；② 反洗动作为滤池置「反洗」，
 * 反洗结束转回「运行」，且只能在 运行 ↔ 反洗 间流转。</p>
 */
@Service
public class FilterService {

    private final FilterRepository repository;
    private final DisposalService disposalService;

    public FilterService(FilterRepository repository, DisposalService disposalService) {
        this.repository = repository;
        this.disposalService = disposalService;
    }

    public List<Filter> list(String status, String type) {
        if (status != null && !status.isBlank()) {
            return repository.findByStatus(status);
        }
        if (type != null && !type.isBlank()) {
            return repository.findByType(type);
        }
        return repository.findAll();
    }

    public Filter get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("滤池不存在：" + id));
    }

    @Transactional
    public Filter create(Filter input) {
        if (input.getFilterCode() == null || input.getFilterCode().isBlank()) {
            throw new BizException("滤池编号不能为空");
        }
        repository.findByFilterCode(input.getFilterCode().trim()).ifPresent(f -> {
            throw new BizException("滤池编号已存在：" + input.getFilterCode());
        });
        if (!Dict.isValidFilterType(input.getType())) {
            throw new BizException("滤池类型必须为 砂滤 / 炭滤");
        }
        if (input.getFlowM3h() == null || input.getFlowM3h().signum() <= 0) {
            throw new BizException("滤速 / 流量必须大于 0");
        }
        Filter filter = new Filter();
        filter.setFilterCode(input.getFilterCode().trim());
        filter.setType(input.getType());
        filter.setFlowM3h(input.getFlowM3h());
        filter.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "运行" : input.getStatus());
        filter.setNote(input.getNote());
        filter.setCreatedAt(LocalDateTime.now());
        return repository.save(filter);
    }

    @Transactional
    public Filter update(Long id, Filter input) {
        Filter filter = get(id);
        if (input.getType() != null && !input.getType().isBlank()) {
            if (!Dict.isValidFilterType(input.getType())) {
                throw new BizException("滤池类型必须为 砂滤 / 炭滤");
            }
            filter.setType(input.getType());
        }
        if (input.getFlowM3h() != null) {
            if (input.getFlowM3h().signum() <= 0) {
                throw new BizException("滤速 / 流量必须大于 0");
            }
            filter.setFlowM3h(input.getFlowM3h());
        }
        if (input.getNote() != null) {
            filter.setNote(input.getNote());
        }
        return repository.save(filter);
    }

    /** 规则②：反洗置状态为「反洗」；同事务把未解除处置单里的关联标为冲突 */
    @Transactional
    public Filter backwash(Long id) {
        Filter filter = get(id);
        if (!Dict.canTransferFilter(filter.getStatus(), "反洗")) {
            throw new BizException("只有「运行」中的滤池可以反洗，当前状态：" + filter.getStatus());
        }
        filter.setStatus("反洗");
        Filter saved = repository.save(filter);
        disposalService.markEquipmentConflicts(Dict.EQUIP_FILTER, id, "反洗");
        return saved;
    }

    /** 反洗结束，转回「运行」 */
    @Transactional
    public Filter resume(Long id) {
        Filter filter = get(id);
        if (!Dict.canTransferFilter(filter.getStatus(), "运行")) {
            throw new BizException("只有「反洗」中的滤池可以恢复运行，当前状态：" + filter.getStatus());
        }
        filter.setStatus("运行");
        return repository.save(filter);
    }

    @Transactional
    public Filter transfer(Long id, String target) {
        Filter filter = get(id);
        if (!Dict.isValidFilterStatus(target)) {
            throw new BizException("滤池状态不合法：" + target);
        }
        if (!Dict.canTransferFilter(filter.getStatus(), target)) {
            throw new BizException("不允许从「" + filter.getStatus() + "」转为「" + target + "」");
        }
        filter.setStatus(target);
        Filter saved = repository.save(filter);
        // 超标处置联动：设备离开「运行」，关联标冲突（恢复运行不自动消除）
        disposalService.markEquipmentConflicts(Dict.EQUIP_FILTER, id, target);
        return saved;
    }
}
