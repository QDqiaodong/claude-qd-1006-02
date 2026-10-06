package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.dto.DisposalDetail;
import com.waterworks.plant.dto.DisposalSummary;
import com.waterworks.plant.entity.Disposal;
import com.waterworks.plant.entity.DisposalDosing;
import com.waterworks.plant.entity.DisposalEquipment;
import com.waterworks.plant.entity.DisposalLog;
import com.waterworks.plant.entity.Dosing;
import com.waterworks.plant.entity.Filter;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.entity.SedimentationTank;
import com.waterworks.plant.repository.DisposalDosingRepository;
import com.waterworks.plant.repository.DisposalEquipmentRepository;
import com.waterworks.plant.repository.DisposalLogRepository;
import com.waterworks.plant.repository.DisposalRepository;
import com.waterworks.plant.repository.DosingRepository;
import com.waterworks.plant.repository.FilterRepository;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.repository.SedimentationTankRepository;

/**
 * 原水超标联动处置闭环：待处置 → 执行中 → 已解除。
 *
 * <p>一致性全部在后端保证：</p>
 * <ul>
 *   <li>并发唯一 —— disposal.active_source 生成列 + 唯一索引兜底，同事务预检给出友好提示；
 *       并发第二单命中唯一约束后转成「已被接管」的明确结果。</li>
 *   <li>状态约束 —— 所有推进先对处置单行加悲观写锁，再按 Dict 状态机校验；
 *       解除在单事务内完成「校验冲突 + 写结论 + 改状态 + 留痕」，失败整体回滚，不留半完成数据。</li>
 *   <li>设备冲突 —— 设备转清洗 / 反洗时由设备服务在同事务内调用 {@link #markEquipmentConflicts}
 *       把关联标为「冲突」；进入执行 / 解除 / 查看详情前再防御性复检一次。</li>
 *   <li>加药一致 —— (disposal_id, chem_name, target_tank) 唯一约束，重复提交幂等返回已存在记录，
 *       不会产生两份加药流水。</li>
 * </ul>
 */
@Service
public class DisposalService {

    private final DisposalRepository disposalRepository;
    private final DisposalEquipmentRepository equipmentRepository;
    private final DisposalDosingRepository disposalDosingRepository;
    private final DisposalLogRepository logRepository;
    private final RawWaterRepository rawWaterRepository;
    private final SedimentationTankRepository tankRepository;
    private final FilterRepository filterRepository;
    private final DosingRepository dosingRepository;
    private final TransactionTemplate txTemplate;

    public DisposalService(DisposalRepository disposalRepository,
                           DisposalEquipmentRepository equipmentRepository,
                           DisposalDosingRepository disposalDosingRepository,
                           DisposalLogRepository logRepository,
                           RawWaterRepository rawWaterRepository,
                           SedimentationTankRepository tankRepository,
                           FilterRepository filterRepository,
                           DosingRepository dosingRepository,
                           PlatformTransactionManager txManager) {
        this.disposalRepository = disposalRepository;
        this.equipmentRepository = equipmentRepository;
        this.disposalDosingRepository = disposalDosingRepository;
        this.logRepository = logRepository;
        this.rawWaterRepository = rawWaterRepository;
        this.tankRepository = tankRepository;
        this.filterRepository = filterRepository;
        this.dosingRepository = dosingRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================== 查询 ====================

    public List<DisposalSummary> list(String status) {
        List<Disposal> disposals = (status != null && !status.isBlank())
                ? disposalRepository.findByStatus(status)
                : disposalRepository.findAll();
        disposals.sort(Comparator.comparing(Disposal::getId).reversed());
        if (disposals.isEmpty()) {
            return List.of();
        }
        List<Long> ids = disposals.stream().map(Disposal::getId).toList();
        Map<Long, List<DisposalEquipment>> linksByDisposal = equipmentRepository.findByDisposalIdIn(ids)
                .stream().collect(Collectors.groupingBy(DisposalEquipment::getDisposalId));
        Map<Long, Long> dosingCountByDisposal = disposalDosingRepository.findByDisposalIdIn(ids)
                .stream().collect(Collectors.groupingBy(DisposalDosing::getDisposalId, Collectors.counting()));
        return disposals.stream().map(d -> {
            List<DisposalEquipment> links = linksByDisposal.getOrDefault(d.getId(), List.of());
            DisposalSummary s = new DisposalSummary();
            s.setId(d.getId());
            s.setRawWaterId(d.getRawWaterId());
            s.setSourceName(d.getSourceName());
            s.setStatus(d.getStatus());
            s.setOwner(d.getOwner());
            s.setTankCount(countLink(links, Dict.EQUIP_TANK));
            s.setFilterCount(countLink(links, Dict.EQUIP_FILTER));
            s.setConflictCount((int) links.stream().filter(l -> Dict.LINK_CONFLICT.equals(l.getLinkStatus())).count());
            s.setDosingCount(dosingCountByDisposal.getOrDefault(d.getId(), 0L).intValue());
            s.setConclusion(d.getConclusion());
            s.setCreatedAt(d.getCreatedAt());
            s.setResolvedAt(d.getResolvedAt());
            return s;
        }).toList();
    }

    /** 详情：锁行 + 防御性复检设备冲突后返回，刷新 / 重新进入看到的就是最新一致状态 */
    public DisposalDetail getDetail(Long id) {
        return txTemplate.execute(status -> {
            Disposal d = lockDisposal(id);
            refreshConflicts(d);
            return buildDetail(d);
        });
    }

    // ==================== 发起 ====================

    /**
     * 发起处置。并发安全：事务内预检 + active_source 唯一索引兜底；
     * 第二单命中唯一约束后抛出「已被接管」的明确提示。
     */
    public DisposalDetail initiate(Long rawWaterId, String owner, List<Long> tankIds, List<Long> filterIds) {
        if (rawWaterId == null) {
            throw new BizException("缺少原水监测记录 id");
        }
        RawWater rw = rawWaterRepository.findById(rawWaterId)
                .orElseThrow(() -> new BizException("原水监测记录不存在：" + rawWaterId));
        if (!"超标".equals(rw.getStatus())) {
            throw new BizException("仅「超标」状态的原水监测记录可发起处置，当前状态：" + rw.getStatus());
        }
        if (owner == null || owner.isBlank()) {
            throw new BizException("责任人（值班人员）不能为空");
        }
        if (tankIds == null || tankIds.isEmpty() || filterIds == null || filterIds.isEmpty()) {
            throw new BizException("发起处置需至少关联一台「运行」中的沉淀池和一台「运行」中的滤池");
        }
        String sourceName = rw.getSourceName();
        if (!disposalRepository.findBySourceNameAndStatusIn(sourceName, Dict.DISPOSAL_ACTIVE).isEmpty()) {
            throw takenOver(sourceName);
        }
        Long id;
        try {
            id = txTemplate.execute(status -> doInitiate(rw, owner.trim(), tankIds, filterIds));
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                // 并发兜底：另一值班人员已抢先接管该水源
                throw takenOver(sourceName);
            }
            throw e;
        }
        return getDetail(id);
    }

    private Long doInitiate(RawWater rw, String owner, List<Long> tankIds, List<Long> filterIds) {
        // 事务内再预检一次（读已提交），友好提示优先于唯一约束报错
        if (!disposalRepository.findBySourceNameAndStatusIn(rw.getSourceName(), Dict.DISPOSAL_ACTIVE).isEmpty()) {
            throw takenOver(rw.getSourceName());
        }
        List<SedimentationTank> tanks = tankIds.stream().distinct().map(this::requireRunningTank).toList();
        List<Filter> filters = filterIds.stream().distinct().map(this::requireRunningFilter).toList();

        LocalDateTime now = LocalDateTime.now();
        Disposal d = new Disposal();
        d.setRawWaterId(rw.getId());
        d.setSourceName(rw.getSourceName());
        d.setStatus(Dict.DISPOSAL_PENDING);
        d.setOwner(owner);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        // saveAndFlush：让 active_source 唯一约束在事务内即刻校验，而不是拖到提交时
        disposalRepository.saveAndFlush(d);

        StringBuilder linked = new StringBuilder();
        for (SedimentationTank t : tanks) {
            addLink(d.getId(), Dict.EQUIP_TANK, t.getId(), t.getTankCode(), now);
            linked.append(linked.length() == 0 ? "" : "、").append(t.getTankCode());
        }
        linked.append("；");
        for (Filter f : filters) {
            addLink(d.getId(), Dict.EQUIP_FILTER, f.getId(), f.getFilterCode(), now);
            linked.append(f.getFilterCode()).append("、");
        }
        if (linked.charAt(linked.length() - 1) == '、') {
            linked.deleteCharAt(linked.length() - 1);
        }
        addLog(d.getId(), "发起", null, Dict.DISPOSAL_PENDING, owner,
                "原水记录 #" + rw.getId() + " 判超标，发起联动处置；关联 " + linked);
        return d.getId();
    }

    // ==================== 状态推进 ====================

    /** 待处置 → 执行中：需至少一台正常关联的沉淀池和一台滤池，且无未处理冲突 */
    public DisposalDetail start(Long id, String operator) {
        return txTemplate.execute(status -> {
            Disposal d = lockDisposal(id);
            requireOperator(operator);
            if (!Dict.canTransferDisposal(d.getStatus(), Dict.DISPOSAL_RUNNING)) {
                throw new BizException("当前状态「" + d.getStatus() + "」不允许进入执行中");
            }
            refreshConflicts(d);
            List<DisposalEquipment> links = equipmentRepository.findByDisposalIdOrderById(d.getId());
            requireNoConflict(links);
            int tanks = countLink(links, Dict.EQUIP_TANK);
            int filters = countLink(links, Dict.EQUIP_FILTER);
            if (tanks < 1 || filters < 1) {
                throw new BizException("进入执行中前需至少确认一台正常关联的沉淀池和一台滤池（当前沉淀池 "
                        + tanks + " 台、滤池 " + filters + " 台）");
            }
            d.setStatus(Dict.DISPOSAL_RUNNING);
            d.setUpdatedAt(LocalDateTime.now());
            disposalRepository.save(d);
            addLog(d.getId(), "进入执行", Dict.DISPOSAL_PENDING, Dict.DISPOSAL_RUNNING, operator.trim(),
                    "确认沉淀池 " + tanks + " 台、滤池 " + filters + " 台，进入执行中");
            return buildDetail(d);
        });
    }

    /**
     * 执行中 → 已解除：单事务内完成「复检冲突 + 写结论与时间 + 改状态 + 留痕」。
     * 任一校验失败抛业务异常，整体回滚，不留半完成数据。
     */
    public DisposalDetail resolve(Long id, String conclusion, String operator) {
        return txTemplate.execute(status -> {
            Disposal d = lockDisposal(id);
            requireOperator(operator);
            if (!Dict.canTransferDisposal(d.getStatus(), Dict.DISPOSAL_RESOLVED)) {
                throw new BizException(Dict.DISPOSAL_RESOLVED.equals(d.getStatus())
                        ? "处置单已解除，请勿重复操作"
                        : "当前状态「" + d.getStatus() + "」不能解除，需先进入执行中");
            }
            if (conclusion == null || conclusion.isBlank()) {
                throw new BizException("解除时必须填写处理结论");
            }
            // 解除前最后复检：设备若在处置期间转入清洗/反洗，必须已改选或排除
            refreshConflicts(d);
            List<DisposalEquipment> links = equipmentRepository.findByDisposalIdOrderById(d.getId());
            requireNoConflict(links);
            LocalDateTime now = LocalDateTime.now();
            d.setStatus(Dict.DISPOSAL_RESOLVED);
            d.setConclusion(conclusion.trim());
            d.setResolvedAt(now);
            d.setUpdatedAt(now);
            disposalRepository.save(d);
            addLog(d.getId(), "解除", Dict.DISPOSAL_RUNNING, Dict.DISPOSAL_RESOLVED, operator.trim(), conclusion.trim());
            return buildDetail(d);
        });
    }

    // ==================== 设备冲突处理 ====================

    /** 改选：把冲突关联替换为一台「运行」中的同类型设备，旧关联留痕为「已改选」 */
    public DisposalDetail replaceEquipment(Long id, Long linkId, Long newEquipId, String operator) {
        return txTemplate.execute(status -> {
            Disposal d = lockDisposal(id);
            requireOperator(operator);
            requireActive(d);
            DisposalEquipment link = equipmentRepository.findById(linkId)
                    .orElseThrow(() -> new BizException("设备关联不存在：" + linkId));
            if (!link.getDisposalId().equals(d.getId())) {
                throw new BizException("设备关联不属于处置单 #" + d.getId());
            }
            if (!Dict.LINK_CONFLICT.equals(link.getLinkStatus())) {
                throw new BizException("仅「冲突」状态的关联需要改选，当前关联状态：" + link.getLinkStatus());
            }
            if (newEquipId == null) {
                throw new BizException("请选择改选后的设备");
            }
            String newCode;
            if (Dict.EQUIP_TANK.equals(link.getEquipType())) {
                newCode = requireRunningTank(newEquipId).getTankCode();
            } else {
                newCode = requireRunningFilter(newEquipId).getFilterCode();
            }
            boolean alreadyLinked = equipmentRepository.findByDisposalIdOrderById(d.getId()).stream()
                    .anyMatch(l -> Dict.LINK_NORMAL.equals(l.getLinkStatus())
                            && l.getEquipType().equals(link.getEquipType())
                            && l.getEquipId().equals(newEquipId));
            if (alreadyLinked) {
                throw new BizException("设备 " + newCode + " 已在本处置单关联中，无需重复改选");
            }
            LocalDateTime now = LocalDateTime.now();
            link.setLinkStatus(Dict.LINK_REPLACED);
            link.setUpdatedAt(now);
            equipmentRepository.save(link);
            addLink(d.getId(), link.getEquipType(), newEquipId, newCode, now);
            addLog(d.getId(), "改选设备", d.getStatus(), d.getStatus(), operator.trim(),
                    link.getEquipCode() + " → " + newCode);
            return buildDetail(d);
        });
    }

    /** 排除：填写排除原因后，冲突关联置为「已排除」 */
    public DisposalDetail excludeEquipment(Long id, Long linkId, String reason, String operator) {
        return txTemplate.execute(status -> {
            Disposal d = lockDisposal(id);
            requireOperator(operator);
            requireActive(d);
            DisposalEquipment link = equipmentRepository.findById(linkId)
                    .orElseThrow(() -> new BizException("设备关联不存在：" + linkId));
            if (!link.getDisposalId().equals(d.getId())) {
                throw new BizException("设备关联不属于处置单 #" + d.getId());
            }
            if (!Dict.LINK_CONFLICT.equals(link.getLinkStatus())) {
                throw new BizException("仅「冲突」状态的关联需要排除，当前关联状态：" + link.getLinkStatus());
            }
            if (reason == null || reason.isBlank()) {
                throw new BizException("排除设备必须填写排除原因");
            }
            link.setLinkStatus(Dict.LINK_EXCLUDED);
            link.setExcludeReason(reason.trim());
            link.setUpdatedAt(LocalDateTime.now());
            equipmentRepository.save(link);
            addLog(d.getId(), "排除设备", d.getStatus(), d.getStatus(), operator.trim(),
                    "排除 " + link.getEquipCode() + "：" + reason.trim());
            return buildDetail(d);
        });
    }

    // ==================== 加药登记（幂等） ====================

    /**
     * 登记本次调整的药剂 / 投加量 / 目标工段。同一处置单内「同药剂 + 同目标工段」只落一条：
     * 先预检，命中唯一约束时按幂等成功返回已存在记录 —— 重复点击不会产生两份记录。
     */
    public DisposalDetail.DosingItem registerDosing(Long id, String chemName, java.math.BigDecimal doseMgPerL,
                                                    String targetTank, String operator) {
        if (!Dict.isValidChemName(chemName)) {
            throw new BizException("药剂必须为 聚合氯化铝 / 次氯酸钠");
        }
        if (doseMgPerL == null || doseMgPerL.signum() <= 0) {
            throw new BizException("投加量必须大于 0（mg/L）");
        }
        if (targetTank == null || targetTank.isBlank()) {
            throw new BizException("目标工段不能为空");
        }
        requireOperator(operator);
        String target = targetTank.trim();
        var existing = disposalDosingRepository.findByDisposalIdAndChemNameAndTargetTank(id, chemName, target);
        if (existing.isPresent()) {
            return toDosingItem(existing.get());
        }
        try {
            return txTemplate.execute(status -> {
                Disposal d = lockDisposal(id);
                requireActive(d);
                LocalDateTime now = LocalDateTime.now();
                Dosing dosing = new Dosing();
                dosing.setChemName(chemName);
                dosing.setDoseMgPerL(doseMgPerL);
                dosing.setTargetTank(target);
                dosing.setDosedAt(now);
                dosing.setNote("处置单#" + d.getId() + " 联动加药");
                dosing.setCreatedAt(now);
                dosingRepository.save(dosing);

                DisposalDosing dd = new DisposalDosing();
                dd.setDisposalId(d.getId());
                dd.setDosingId(dosing.getId());
                dd.setChemName(chemName);
                dd.setDoseMgPerL(doseMgPerL);
                dd.setTargetTank(target);
                dd.setOperatorName(operator.trim());
                dd.setCreatedAt(now);
                // saveAndFlush：唯一约束在事务内即刻校验
                disposalDosingRepository.saveAndFlush(dd);
                addLog(d.getId(), "登记加药", d.getStatus(), d.getStatus(), operator.trim(),
                        chemName + " " + doseMgPerL + " mg/L → " + target);
                return toDosingItem(dd);
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                // 并发重复提交：返回已存在的那一条，不重复建档
                return disposalDosingRepository.findByDisposalIdAndChemNameAndTargetTank(id, chemName, target)
                        .map(this::toDosingItem)
                        .orElseThrow(() -> e);
            }
            throw e;
        }
    }

    // ==================== 设备状态流转钩子（由沉淀池 / 滤池服务在同事务内调用） ====================

    /**
     * 设备离开「运行」（清洗 / 反洗）时，把未解除处置单里该设备的「正常」关联标为「冲突」。
     * 关联绝不悄悄失效；恢复运行也不自动消除冲突，须值班人员改选或排除。
     */
    public void markEquipmentConflicts(String equipType, Long equipId, String newStatus) {
        if (Dict.EQUIP_RUNNING.equals(newStatus)) {
            return;
        }
        List<DisposalEquipment> links = equipmentRepository
                .findByEquipTypeAndEquipIdAndLinkStatus(equipType, equipId, Dict.LINK_NORMAL);
        LocalDateTime now = LocalDateTime.now();
        for (DisposalEquipment link : links) {
            Disposal d = disposalRepository.findById(link.getDisposalId()).orElse(null);
            if (d == null || !Dict.DISPOSAL_ACTIVE.contains(d.getStatus())) {
                continue;
            }
            link.setLinkStatus(Dict.LINK_CONFLICT);
            link.setConflictReason("设备已转入" + newStatus);
            link.setUpdatedAt(now);
            equipmentRepository.save(link);
            addLog(d.getId(), "设备冲突", d.getStatus(), d.getStatus(), "系统",
                    link.getEquipCode() + " 设备已转入" + newStatus + "，关联标为冲突");
        }
    }

    // ==================== 内部工具 ====================

    private Disposal lockDisposal(Long id) {
        return disposalRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("处置单不存在：" + id));
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作人不能为空");
        }
    }

    private void requireActive(Disposal d) {
        if (!Dict.DISPOSAL_ACTIVE.contains(d.getStatus())) {
            throw new BizException("处置单已解除，不能再操作");
        }
    }

    private void requireNoConflict(List<DisposalEquipment> links) {
        long conflicts = links.stream().filter(l -> Dict.LINK_CONFLICT.equals(l.getLinkStatus())).count();
        if (conflicts > 0) {
            String codes = links.stream()
                    .filter(l -> Dict.LINK_CONFLICT.equals(l.getLinkStatus()))
                    .map(DisposalEquipment::getEquipCode)
                    .collect(Collectors.joining("、"));
            throw new BizException("存在 " + conflicts + " 条设备冲突未处理（" + codes + "），请改选可用设备或填写排除原因后再继续");
        }
    }

    private SedimentationTank requireRunningTank(Long id) {
        SedimentationTank t = tankRepository.findById(id)
                .orElseThrow(() -> new BizException("沉淀池不存在：" + id));
        if (!Dict.EQUIP_RUNNING.equals(t.getStatus())) {
            throw new BizException("沉淀池 " + t.getTankCode() + " 当前状态为「" + t.getStatus() + "」，仅「运行」中的设备可关联");
        }
        return t;
    }

    private Filter requireRunningFilter(Long id) {
        Filter f = filterRepository.findById(id)
                .orElseThrow(() -> new BizException("滤池不存在：" + id));
        if (!Dict.EQUIP_RUNNING.equals(f.getStatus())) {
            throw new BizException("滤池 " + f.getFilterCode() + " 当前状态为「" + f.getStatus() + "」，仅「运行」中的设备可关联");
        }
        return f;
    }

    /** 防御性复检：活跃处置单的「正常」关联若对应设备已不在「运行」，立即标冲突并留痕 */
    private void refreshConflicts(Disposal d) {
        if (!Dict.DISPOSAL_ACTIVE.contains(d.getStatus())) {
            return;
        }
        List<DisposalEquipment> links = equipmentRepository.findByDisposalIdOrderById(d.getId());
        LocalDateTime now = LocalDateTime.now();
        for (DisposalEquipment link : links) {
            if (!Dict.LINK_NORMAL.equals(link.getLinkStatus())) {
                continue;
            }
            String current = currentEquipStatus(link.getEquipType(), link.getEquipId());
            if (!Dict.EQUIP_RUNNING.equals(current)) {
                link.setLinkStatus(Dict.LINK_CONFLICT);
                link.setConflictReason(current == null ? "设备已不存在" : "设备已转入" + current);
                link.setUpdatedAt(now);
                equipmentRepository.save(link);
                addLog(d.getId(), "设备冲突", d.getStatus(), d.getStatus(), "系统",
                        link.getEquipCode() + " " + link.getConflictReason() + "，关联标为冲突");
            }
        }
    }

    private String currentEquipStatus(String equipType, Long equipId) {
        if (Dict.EQUIP_TANK.equals(equipType)) {
            return tankRepository.findById(equipId).map(SedimentationTank::getStatus).orElse(null);
        }
        return filterRepository.findById(equipId).map(Filter::getStatus).orElse(null);
    }

    private void addLink(Long disposalId, String equipType, Long equipId, String equipCode, LocalDateTime now) {
        DisposalEquipment link = new DisposalEquipment();
        link.setDisposalId(disposalId);
        link.setEquipType(equipType);
        link.setEquipId(equipId);
        link.setEquipCode(equipCode);
        link.setLinkStatus(Dict.LINK_NORMAL);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        equipmentRepository.save(link);
    }

    private void addLog(Long disposalId, String action, String from, String to, String operator, String detail) {
        DisposalLog log = new DisposalLog();
        log.setDisposalId(disposalId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorName(operator);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    private int countLink(List<DisposalEquipment> links, String equipType) {
        return (int) links.stream()
                .filter(l -> equipType.equals(l.getEquipType()) && Dict.LINK_NORMAL.equals(l.getLinkStatus()))
                .count();
    }

    private BizException takenOver(String sourceName) {
        List<Disposal> actives = disposalRepository.findBySourceNameAndStatusIn(sourceName, Dict.DISPOSAL_ACTIVE);
        if (!actives.isEmpty()) {
            Disposal d = actives.get(0);
            return new BizException("该水源的超标处置已被「" + d.getOwner() + "」接管（处置单 #" + d.getId()
                    + "，状态：" + d.getStatus() + "），请勿重复发起");
        }
        return new BizException("该水源已存在未解除的处置单，请勿重复发起");
    }

    /** 唯一约束冲突识别：本流程中可能命中的只有 active_source / uk_dd_action 两个唯一键 */
    private boolean isUniqueViolation(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof DataIntegrityViolationException
                    || cur instanceof org.hibernate.exception.ConstraintViolationException
                    || cur instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private DisposalDetail buildDetail(Disposal d) {
        DisposalDetail detail = new DisposalDetail();
        detail.setId(d.getId());
        detail.setRawWaterId(d.getRawWaterId());
        detail.setSourceName(d.getSourceName());
        detail.setStatus(d.getStatus());
        detail.setOwner(d.getOwner());
        detail.setConclusion(d.getConclusion());
        detail.setCreatedAt(d.getCreatedAt());
        detail.setResolvedAt(d.getResolvedAt());

        detail.setEquipments(equipmentRepository.findByDisposalIdOrderById(d.getId()).stream().map(l -> {
            DisposalDetail.EquipLink v = new DisposalDetail.EquipLink();
            v.setLinkId(l.getId());
            v.setEquipType(l.getEquipType());
            v.setEquipId(l.getEquipId());
            v.setEquipCode(l.getEquipCode());
            v.setLinkStatus(l.getLinkStatus());
            String current = currentEquipStatus(l.getEquipType(), l.getEquipId());
            v.setCurrentEquipStatus(current == null ? "已删除" : current);
            v.setConflictReason(l.getConflictReason());
            v.setExcludeReason(l.getExcludeReason());
            v.setCreatedAt(l.getCreatedAt());
            return v;
        }).toList());

        detail.setDosings(disposalDosingRepository.findByDisposalIdOrderById(d.getId()).stream()
                .map(this::toDosingItem).toList());

        detail.setLogs(logRepository.findByDisposalIdOrderById(d.getId()).stream().map(l -> {
            DisposalDetail.LogItem v = new DisposalDetail.LogItem();
            v.setId(l.getId());
            v.setAction(l.getAction());
            v.setFromStatus(l.getFromStatus());
            v.setToStatus(l.getToStatus());
            v.setOperatorName(l.getOperatorName());
            v.setDetail(l.getDetail());
            v.setCreatedAt(l.getCreatedAt());
            return v;
        }).toList());
        return detail;
    }

    private DisposalDetail.DosingItem toDosingItem(DisposalDosing dd) {
        DisposalDetail.DosingItem v = new DisposalDetail.DosingItem();
        v.setId(dd.getId());
        v.setDosingId(dd.getDosingId());
        v.setChemName(dd.getChemName());
        v.setDoseMgPerL(dd.getDoseMgPerL());
        v.setTargetTank(dd.getTargetTank());
        v.setOperatorName(dd.getOperatorName());
        v.setCreatedAt(dd.getCreatedAt());
        return v;
    }
}
