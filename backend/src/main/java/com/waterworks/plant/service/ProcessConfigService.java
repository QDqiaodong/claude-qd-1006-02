package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.entity.ProcessConfig;
import com.waterworks.plant.repository.ProcessConfigRepository;

/**
 * 工艺配置：当前有效的 药剂 / 目标工段。
 *
 * <p>加药登记与修改时，药剂和目标工段必须在启用项内，否则不能登记为有效记录；
 * 配置调整（新增 / 停用 / 启用）只影响之后的登记，历史记录不回写。</p>
 */
@Service
public class ProcessConfigService {

    private final ProcessConfigRepository repository;

    public ProcessConfigService(ProcessConfigRepository repository) {
        this.repository = repository;
    }

    public List<ProcessConfig> list(String itemType) {
        if (itemType != null && !itemType.isBlank()) {
            return repository.findByItemTypeOrderById(itemType.trim());
        }
        return repository.findAll();
    }

    /** 当前启用项名称集合（登记校验与「配置已变更」标记共用同一口径） */
    public Set<String> enabledNames(String itemType) {
        return repository.findByItemTypeAndEnabledTrue(itemType).stream()
                .map(ProcessConfig::getItemName)
                .collect(Collectors.toSet());
    }

    /** 登记硬约束：药剂必须在当前工艺配置启用项内 */
    public void requireChemInConfig(String chemName) {
        if (!enabledNames(Dict.CONFIG_CHEM).contains(chemName)) {
            throw new BizException("药剂「" + chemName + "」不在当前工艺配置中，不能登记为有效记录");
        }
    }

    /** 登记硬约束：目标工段必须在当前工艺配置启用项内 */
    public void requireTargetInConfig(String targetSection) {
        if (!enabledNames(Dict.CONFIG_TARGET).contains(targetSection)) {
            throw new BizException("目标工段「" + targetSection + "」不在当前工艺配置中，不能登记为有效记录");
        }
    }

    /** 新增配置项（仅管理员）；同类型同名不允许重复 */
    @Transactional
    public ProcessConfig add(String itemType, String itemName, String role) {
        requireAdmin(role);
        if (!Dict.isValidConfigType(itemType)) {
            throw new BizException("配置类型必须为 药剂 / 目标工段");
        }
        if (itemName == null || itemName.isBlank()) {
            throw new BizException("配置项名称不能为空");
        }
        String name = itemName.trim();
        if (name.length() > 32) {
            throw new BizException("配置项名称不能超过 32 字");
        }
        if (repository.findByItemTypeAndItemName(itemType, name).isPresent()) {
            throw new BizException(itemType + "「" + name + "」已在工艺配置中，请勿重复添加");
        }
        LocalDateTime now = LocalDateTime.now();
        ProcessConfig cfg = new ProcessConfig();
        cfg.setItemType(itemType);
        cfg.setItemName(name);
        cfg.setEnabled(true);
        cfg.setCreatedAt(now);
        cfg.setUpdatedAt(now);
        return repository.save(cfg);
    }

    /** 启用 / 停用配置项（仅管理员）。停用后新登记不可选，历史记录保留并标注「配置已变更」。 */
    @Transactional
    public ProcessConfig toggle(Long id, boolean enabled, String role) {
        requireAdmin(role);
        ProcessConfig cfg = repository.findById(id)
                .orElseThrow(() -> new BizException("配置项不存在：" + id));
        cfg.setEnabled(enabled);
        cfg.setUpdatedAt(LocalDateTime.now());
        return repository.save(cfg);
    }

    private void requireAdmin(String role) {
        if (!Dict.isAdmin(role)) {
            throw new BizException("仅管理员可维护工艺配置");
        }
    }
}
