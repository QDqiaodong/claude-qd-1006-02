package com.waterworks.plant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.dto.DosingRecordDetail;
import com.waterworks.plant.dto.DosingRecordSummary;
import com.waterworks.plant.entity.DosingRecord;
import com.waterworks.plant.entity.DosingRecordLog;
import com.waterworks.plant.repository.DosingRecordLogRepository;
import com.waterworks.plant.repository.DosingRecordRepository;

/**
 * 加药批次记录与异常复核：待确认 → 已确认；待确认 → 异常待复核 → 已确认 / 已退回。
 *
 * <p>一致性全部在后端保证：</p>
 * <ul>
 *   <li>批次唯一 —— (target_section, execute_at) 唯一索引兜底，同事务预检给出友好提示；
 *       重复点击 / 网络重试 / 两人并发登记，第二个请求命中唯一约束后转成
 *       「已存在批次 #N（当前状态…）」的明确结果，只保留一个批次，不会分叉。</li>
 *   <li>状态约束 —— 确认 / 标记异常 / 复核都先对记录行加悲观写锁，再按 Dict 状态机校验，
 *       并发复核串行化，后到者看到最新状态。</li>
 *   <li>权限一致 —— 登记 / 修改要求运行工或管理员；修改仅限本人提交且「待确认」的记录；
 *       确认 / 标记异常 / 复核退回 / 查看全部异常仅管理员。页面按钮与后端校验同一口径。</li>
 *   <li>历史不改写 —— 退回保留原始登记与原因；工艺配置调整不回写历史记录，
 *       读取时对比当前配置计算「配置已变更」标记。</li>
 * </ul>
 */
@Service
public class DosingRecordService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DosingRecordRepository repository;
    private final DosingRecordLogRepository logRepository;
    private final ProcessConfigService configService;
    private final TransactionTemplate txTemplate;

    public DosingRecordService(DosingRecordRepository repository,
                               DosingRecordLogRepository logRepository,
                               ProcessConfigService configService,
                               PlatformTransactionManager txManager) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.configService = configService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================== 查询 ====================

    /** 列表：按目标工段 / 状态 / 执行时间区间追溯 */
    public List<DosingRecordSummary> list(String targetSection, String status,
                                          LocalDateTime executeFrom, LocalDateTime executeTo) {
        Set<String> chems = configService.enabledNames(Dict.CONFIG_CHEM);
        Set<String> targets = configService.enabledNames(Dict.CONFIG_TARGET);
        return repository.findAll().stream()
                .filter(r -> targetSection == null || targetSection.isBlank()
                        || r.getTargetSection().equals(targetSection.trim()))
                .filter(r -> status == null || status.isBlank() || r.getStatus().equals(status.trim()))
                .filter(r -> executeFrom == null || !r.getExecuteAt().isBefore(executeFrom))
                .filter(r -> executeTo == null || !r.getExecuteAt().isAfter(executeTo))
                .sorted(Comparator.comparing(DosingRecord::getExecuteAt).reversed()
                        .thenComparing(Comparator.comparing(DosingRecord::getId).reversed()))
                .map(r -> toSummary(r, chems, targets))
                .toList();
    }

    public DosingRecordDetail getDetail(Long id) {
        DosingRecord r = repository.findById(id)
                .orElseThrow(() -> new BizException("加药批次不存在：" + id));
        return toDetail(r);
    }

    /** 全部异常（异常待复核 + 已退回）：仅管理员可查 */
    public List<DosingRecordSummary> anomalies(String role) {
        requireAdmin(role);
        Set<String> chems = configService.enabledNames(Dict.CONFIG_CHEM);
        Set<String> targets = configService.enabledNames(Dict.CONFIG_TARGET);
        return repository.findByStatusIn(List.of(Dict.DOSING_ANOMALY, Dict.DOSING_RETURNED)).stream()
                .sorted(Comparator.comparing((DosingRecord r) -> Dict.DOSING_ANOMALY.equals(r.getStatus()) ? 0 : 1)
                        .thenComparing(Comparator.comparing(DosingRecord::getId).reversed()))
                .map(r -> toSummary(r, chems, targets))
                .toList();
    }

    // ==================== 登记（幂等） ====================

    /**
     * 登记加药批次。提交前必须填写完整（药剂 / 投加量 / 目标工段 / 执行时间 / 操作者）；
     * 药剂与目标工段必须在当前工艺配置启用项内，否则不能登记为有效记录。
     * 同一目标工段同一执行时间只保留一个批次：先预检，命中唯一约束时
     * 转成「已存在批次 #N（当前状态…）」的明确提示，失败方能看到已存在记录。
     */
    public DosingRecordDetail create(String chemName, BigDecimal doseMgPerL, String targetSection,
                                     LocalDateTime executeAt, String operator, String note, String role) {
        if (!Dict.canRegisterDosing(role)) {
            throw new BizException("仅运行工 / 管理员可登记加药批次");
        }
        String chem = requireText(chemName, "药剂不能为空");
        String target = requireText(targetSection, "目标工段不能为空");
        String op = requireText(operator, "操作者不能为空");
        requireDose(doseMgPerL);
        if (executeAt == null) {
            throw new BizException("执行时间不能为空");
        }
        checkLength(note, 128, "备注");
        // 工艺配置硬约束：不在当前配置中的药剂 / 目标工段不能登记为有效记录
        configService.requireChemInConfig(chem);
        configService.requireTargetInConfig(target);

        var existing = repository.findByTargetSectionAndExecuteAt(target, executeAt);
        if (existing.isPresent()) {
            throw duplicate(existing.get());
        }
        Long id;
        try {
            id = txTemplate.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                DosingRecord r = new DosingRecord();
                r.setChemName(chem);
                r.setDoseMgPerL(doseMgPerL);
                r.setTargetSection(target);
                r.setExecuteAt(executeAt);
                r.setOperatorName(op);
                r.setNote(note == null || note.isBlank() ? null : note.trim());
                r.setStatus(Dict.DOSING_PENDING);
                r.setCreatedAt(now);
                r.setUpdatedAt(now);
                // saveAndFlush：唯一约束在事务内即刻校验，而不是拖到提交时
                repository.saveAndFlush(r);
                addLog(r.getId(), "登记", null, Dict.DOSING_PENDING, op, describe(r));
                return r.getId();
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                // 并发兜底：另一运行工已抢先登记同工段同时间的批次
                throw repository.findByTargetSectionAndExecuteAt(target, executeAt)
                        .map(this::duplicate).orElseThrow(() -> e);
            }
            throw e;
        }
        return getDetail(id);
    }

    // ==================== 修改（本人 + 待确认） ====================

    /**
     * 修改登记内容。仅登记人本人、且记录仍为「待确认」时可改；
     * 已确认 / 异常待复核 / 已退回的记录一律冻结，复核退回不得覆盖成另一条正常记录。
     */
    public DosingRecordDetail update(Long id, String chemName, BigDecimal doseMgPerL, String targetSection,
                                     LocalDateTime executeAt, String operator, String note, String role) {
        if (!Dict.canRegisterDosing(role)) {
            throw new BizException("仅运行工 / 管理员可维护加药记录");
        }
        String op = requireText(operator, "操作者不能为空");
        String chem = requireText(chemName, "药剂不能为空");
        String target = requireText(targetSection, "目标工段不能为空");
        requireDose(doseMgPerL);
        if (executeAt == null) {
            throw new BizException("执行时间不能为空");
        }
        checkLength(note, 128, "备注");
        configService.requireChemInConfig(chem);
        configService.requireTargetInConfig(target);

        try {
            Long rid = txTemplate.execute(status -> {
                DosingRecord r = lock(id);
                if (!Dict.DOSING_PENDING.equals(r.getStatus())) {
                    throw new BizException("仅「待确认」状态的记录可修改，当前状态：" + r.getStatus());
                }
                if (!r.getOperatorName().equals(op)) {
                    throw new BizException("只能维护本人提交的记录（登记人：" + r.getOperatorName() + "）");
                }
                // 唯一槽位（排除自身）：改后的 工段 + 执行时间 不能撞上别的批次
                var clash = repository.findByTargetSectionAndExecuteAt(target, executeAt);
                if (clash.isPresent() && !clash.get().getId().equals(r.getId())) {
                    throw duplicate(clash.get());
                }
                String before = describe(r);
                r.setChemName(chem);
                r.setDoseMgPerL(doseMgPerL);
                r.setTargetSection(target);
                r.setExecuteAt(executeAt);
                r.setNote(note == null || note.isBlank() ? null : note.trim());
                r.setUpdatedAt(LocalDateTime.now());
                repository.saveAndFlush(r);
                addLog(r.getId(), "修改", r.getStatus(), r.getStatus(), op, "原：" + before + " → 新：" + describe(r));
                return r.getId();
            });
            return getDetail(rid);
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                throw repository.findByTargetSectionAndExecuteAt(target, executeAt)
                        .map(this::duplicate).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    // ==================== 确认 / 复核（仅管理员） ====================

    /**
     * 确认：待确认 → 已确认（核对现场回执）；异常待复核 → 已确认（复核通过）。
     * 回执核对说明可选，填写后随留痕保存。
     */
    public DosingRecordDetail confirm(Long id, String operator, String role, String note) {
        requireAdmin(role);
        String op = requireText(operator, "操作人不能为空");
        checkLength(note, 255, "回执核对说明");
        Long rid = txTemplate.execute(status -> {
            DosingRecord r = lock(id);
            if (Dict.DOSING_CONFIRMED.equals(r.getStatus())) {
                throw new BizException("该批次已确认，请勿重复操作");
            }
            if (!Dict.canTransferDosingRecord(r.getStatus(), Dict.DOSING_CONFIRMED)) {
                throw new BizException("当前状态「" + r.getStatus() + "」不能转为已确认");
            }
            String from = r.getStatus();
            boolean reviewPass = Dict.DOSING_ANOMALY.equals(from);
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(Dict.DOSING_CONFIRMED);
            r.setConfirmedBy(op);
            r.setConfirmedAt(now);
            if (reviewPass) {
                // 异常复核通过：复核人与复核结论留痕
                r.setReviewedBy(op);
                r.setReviewedAt(now);
                r.setReviewReason(note == null || note.isBlank() ? "复核确认投加无误" : note.trim());
            }
            r.setUpdatedAt(now);
            repository.save(r);
            addLog(r.getId(), reviewPass ? "复核通过" : "确认", from, Dict.DOSING_CONFIRMED, op,
                    note == null || note.isBlank() ? "核对现场回执无误，确认为有效批次" : "核对现场回执：" + note.trim());
            return r.getId();
        });
        return getDetail(rid);
    }

    /** 标记异常：待确认 → 异常待复核。发现投加量 / 药剂 / 目标工段异常时由管理员标记，必须说明原因。 */
    public DosingRecordDetail markAnomaly(Long id, String operator, String role, String reason) {
        requireAdmin(role);
        String op = requireText(operator, "操作人不能为空");
        String rs = requireText(reason, "标记异常必须说明原因");
        checkLength(rs, 255, "异常原因");
        Long rid = txTemplate.execute(status -> {
            DosingRecord r = lock(id);
            if (!Dict.canTransferDosingRecord(r.getStatus(), Dict.DOSING_ANOMALY)) {
                throw new BizException("当前状态「" + r.getStatus() + "」不能标记异常");
            }
            r.setStatus(Dict.DOSING_ANOMALY);
            r.setAnomalyReason(rs);
            r.setUpdatedAt(LocalDateTime.now());
            repository.save(r);
            addLog(r.getId(), "标记异常", Dict.DOSING_PENDING, Dict.DOSING_ANOMALY, op, rs);
            return r.getId();
        });
        return getDetail(rid);
    }

    /**
     * 复核退回：异常待复核 → 已退回。必须记录原因；原始登记（药剂 / 投加量 /
     * 目标工段 / 执行时间 / 操作者）一律保留不动，不能覆盖成另一条正常记录。
     */
    public DosingRecordDetail reviewReturn(Long id, String operator, String role, String reason) {
        requireAdmin(role);
        String op = requireText(operator, "操作人不能为空");
        String rs = requireText(reason, "复核退回必须记录原因");
        checkLength(rs, 255, "退回原因");
        Long rid = txTemplate.execute(status -> {
            DosingRecord r = lock(id);
            if (!Dict.canTransferDosingRecord(r.getStatus(), Dict.DOSING_RETURNED)) {
                throw new BizException("当前状态「" + r.getStatus() + "」不能复核退回");
            }
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(Dict.DOSING_RETURNED);
            r.setReviewedBy(op);
            r.setReviewedAt(now);
            r.setReviewReason(rs);
            r.setUpdatedAt(now);
            repository.save(r);
            addLog(r.getId(), "复核退回", Dict.DOSING_ANOMALY, Dict.DOSING_RETURNED, op,
                    "退回原因：" + rs + "；原始登记保留，不得覆盖");
            return r.getId();
        });
        return getDetail(rid);
    }

    // ==================== 内部工具 ====================

    private DosingRecord lock(Long id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("加药批次不存在：" + id));
    }

    private void requireAdmin(String role) {
        if (!Dict.isAdmin(role)) {
            throw new BizException("仅管理员可执行复核类操作");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(message);
        }
        return value.trim();
    }

    private void requireDose(BigDecimal doseMgPerL) {
        if (doseMgPerL == null || doseMgPerL.signum() <= 0) {
            throw new BizException("投加量必须大于 0（mg/L）");
        }
    }

    private void checkLength(String value, int max, String label) {
        if (value != null && value.trim().length() > max) {
            throw new BizException(label + "不能超过 " + max + " 字");
        }
    }

    private String describe(DosingRecord r) {
        return r.getChemName() + " " + r.getDoseMgPerL() + " mg/L → " + r.getTargetSection()
                + "，执行时间 " + r.getExecuteAt().format(FMT);
    }

    /** 重复登记提示：失败方能看到已存在记录及其当前状态 */
    private BizException duplicate(DosingRecord r) {
        return new BizException("目标工段「" + r.getTargetSection() + "」在执行时间 "
                + r.getExecuteAt().format(FMT) + " 已存在加药批次 #" + r.getId()
                + "（状态：" + r.getStatus() + "，操作者：" + r.getOperatorName()
                + "），同一工段同一执行时间只允许一个批次，请勿重复登记");
    }

    /** 唯一约束冲突识别：本流程中可能命中的只有 uk_dr_batch 一个唯一键 */
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

    private void addLog(Long recordId, String action, String from, String to, String operator, String detail) {
        DosingRecordLog log = new DosingRecordLog();
        log.setRecordId(recordId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorName(operator);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    private DosingRecordSummary toSummary(DosingRecord r, Set<String> chems, Set<String> targets) {
        DosingRecordSummary s = new DosingRecordSummary();
        s.setId(r.getId());
        s.setChemName(r.getChemName());
        s.setDoseMgPerL(r.getDoseMgPerL());
        s.setTargetSection(r.getTargetSection());
        s.setExecuteAt(r.getExecuteAt());
        s.setOperatorName(r.getOperatorName());
        s.setNote(r.getNote());
        s.setStatus(r.getStatus());
        s.setAnomalyReason(r.getAnomalyReason());
        s.setReviewReason(r.getReviewReason());
        s.setConfirmedBy(r.getConfirmedBy());
        s.setConfirmedAt(r.getConfirmedAt());
        s.setReviewedBy(r.getReviewedBy());
        s.setReviewedAt(r.getReviewedAt());
        // 登记后工艺配置发生调整 → 标出「配置已变更」，历史值不回写
        s.setChemOutOfConfig(!chems.contains(r.getChemName()));
        s.setTargetOutOfConfig(!targets.contains(r.getTargetSection()));
        s.setCreatedAt(r.getCreatedAt());
        s.setUpdatedAt(r.getUpdatedAt());
        return s;
    }

    private DosingRecordDetail toDetail(DosingRecord r) {
        DosingRecordSummary base = toSummary(r,
                configService.enabledNames(Dict.CONFIG_CHEM),
                configService.enabledNames(Dict.CONFIG_TARGET));
        DosingRecordDetail d = new DosingRecordDetail();
        d.setId(base.getId());
        d.setChemName(base.getChemName());
        d.setDoseMgPerL(base.getDoseMgPerL());
        d.setTargetSection(base.getTargetSection());
        d.setExecuteAt(base.getExecuteAt());
        d.setOperatorName(base.getOperatorName());
        d.setNote(base.getNote());
        d.setStatus(base.getStatus());
        d.setAnomalyReason(base.getAnomalyReason());
        d.setReviewReason(base.getReviewReason());
        d.setConfirmedBy(base.getConfirmedBy());
        d.setConfirmedAt(base.getConfirmedAt());
        d.setReviewedBy(base.getReviewedBy());
        d.setReviewedAt(base.getReviewedAt());
        d.setChemOutOfConfig(base.isChemOutOfConfig());
        d.setTargetOutOfConfig(base.isTargetOutOfConfig());
        d.setCreatedAt(base.getCreatedAt());
        d.setUpdatedAt(base.getUpdatedAt());
        d.setLogs(logRepository.findByRecordIdOrderById(r.getId()).stream().map(l -> {
            DosingRecordDetail.LogItem v = new DosingRecordDetail.LogItem();
            v.setId(l.getId());
            v.setAction(l.getAction());
            v.setFromStatus(l.getFromStatus());
            v.setToStatus(l.getToStatus());
            v.setOperatorName(l.getOperatorName());
            v.setDetail(l.getDetail());
            v.setCreatedAt(l.getCreatedAt());
            return v;
        }).toList());
        return d;
    }
}
