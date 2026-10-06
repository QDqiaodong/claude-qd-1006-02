package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.entity.RawWaterJudgmentLog;
import com.waterworks.plant.repository.RawWaterJudgmentLogRepository;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.type.WaterSample;

/**
 * 原水监测业务。
 *
 * <p>核心规则：① 建档时 pH 必须 6.5~8.5、浊度 < 3 NTU、余氯 0.3~4 mg/L，否则拒绝建档；
 * ② 通过硬校验后按软预警带自动判「合格 / 预警」；③「超标」等人工重判仅管理员可执行。</p>
 *
 * <p>人工重判的一致性与越权防护全部在后端保证（与页面按钮同一口径）：</p>
 * <ul>
 *   <li>权限 —— 仅管理员可重判；角色缺失 / 无法确认身份的请求一律明确拒绝，
 *       样本结论、更新时间与已有裁决日志保持不动。</li>
 *   <li>留痕 —— 重判必须填写操作原因；每次成功重判在同事务内追加一条裁决日志
 *       （操作者 / 时间 / 重判前后结论 / 原因 / 请求标识），日志只增不改，逐次独立追溯。</li>
 *   <li>幂等 —— 客户端每次发起生成 requestId；同一请求重放（快速连点 / 网络重试）
 *       命中已有日志时直接复用首次裁决结果，只留一份日志；request_id 唯一索引兜底。</li>
 *   <li>并发 —— 重判先对样本行加悲观写锁串行化；基于过期页面或与他人并发时，
 *       后到请求得到一致的「裁决冲突」提示，不会静默覆盖先到裁决，
 *       最终结论与日志顺序始终一致。</li>
 * </ul>
 */
@Service
public class RawWaterService {

    private final RawWaterRepository repository;
    private final RawWaterJudgmentLogRepository logRepository;
    private final TransactionTemplate txTemplate;

    public RawWaterService(RawWaterRepository repository,
                           RawWaterJudgmentLogRepository logRepository,
                           PlatformTransactionManager txManager) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public List<RawWater> list(String status, String sourceName) {
        if (status != null && !status.isBlank()) {
            return repository.findByStatus(status);
        }
        if (sourceName != null && !sourceName.isBlank()) {
            return repository.findBySourceName(sourceName);
        }
        return repository.findAll();
    }

    public RawWater get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("原水监测记录不存在：" + id));
    }

    /** 某条原水记录的全部裁决日志（按入库顺序，只增不改） */
    public List<RawWaterJudgmentLog> judgmentLogs(Long id) {
        get(id);
        return logRepository.findByRawWaterIdOrderById(id);
    }

    @Transactional
    public RawWater create(RawWater input) {
        if (input.getSourceName() == null || input.getSourceName().isBlank()) {
            throw new BizException("水源名称不能为空");
        }
        WaterSample ws = input.getWaterSample();
        if (ws == null) {
            throw new BizException("水质水样不能为空");
        }
        // 规则①：硬校验，不满足直接拒绝建档
        String fail = Dict.checkRawWater(ws);
        if (fail != null) {
            throw new BizException(fail);
        }
        RawWater rw = new RawWater();
        rw.setSourceName(input.getSourceName().trim());
        rw.setWaterSample(ws);
        rw.setSampleAt(input.getSampleAt() == null ? LocalDateTime.now() : input.getSampleAt());
        // 规则②：自动判级
        rw.setStatus(Dict.classifyRawWater(ws));
        LocalDateTime now = LocalDateTime.now();
        rw.setCreatedAt(now);
        rw.setUpdatedAt(now);
        return repository.save(rw);
    }

    @Transactional
    public RawWater update(Long id, RawWater input) {
        RawWater rw = get(id);
        if (input.getSourceName() != null && !input.getSourceName().isBlank()) {
            rw.setSourceName(input.getSourceName().trim());
        }
        if (input.getWaterSample() != null) {
            rw.setWaterSample(input.getWaterSample());
        }
        if (input.getSampleAt() != null) {
            rw.setSampleAt(input.getSampleAt());
        }
        rw.setUpdatedAt(LocalDateTime.now());
        return repository.save(rw);
    }

    // ==================== 人工重判（仅管理员，留痕 + 幂等 + 串行化） ====================

    /**
     * 管理员人工重判水质结论。
     *
     * @param id        原水监测记录 id
     * @param status    重判目标结论（合格 / 预警 / 超标）
     * @param fromStatus 提交时所见结论（乐观并发校验，防止基于过期页面静默覆盖他人裁决）
     * @param operator  操作人姓名
     * @param role      操作角色，必须为「管理员」；缺失或无法确认身份一律拒绝
     * @param reason    操作原因（必填，随裁决日志留痕）
     * @param requestId 客户端请求标识（幂等键）：同一请求重放复用首次结果，只留一份日志
     */
    public RawWater reclassify(Long id, String status, String fromStatus,
                               String operator, String role, String reason, String requestId) {
        requireAdmin(role);
        String target = requireText(status, "重判目标结论不能为空");
        if (!Dict.isValidRawWaterStatus(target)) {
            throw new BizException("水质状态不合法：" + target);
        }
        String from = requireText(fromStatus, "缺少重判基准结论（fromStatus），请刷新列表后重新发起");
        String op = requireText(operator, "操作人不能为空");
        String rs = requireText(reason, "人工重判必须填写操作原因");
        String reqId = requireText(requestId, "缺少请求标识（requestId），请重新发起");
        checkLength(op, 32, "操作人");
        checkLength(rs, 255, "操作原因");
        checkLength(reqId, 64, "请求标识");

        try {
            return txTemplate.execute(s -> {
                // 先锁样本行：多人同时操作同一样本时在此串行化
                RawWater rw = lock(id);
                // ① 同一请求重放：锁内再查，已落日志则复用首次裁决结果，绝不重复留痕
                var dup = logRepository.findByRequestId(reqId);
                if (dup.isPresent()) {
                    RawWaterJudgmentLog log = dup.get();
                    if (log.getRawWaterId().equals(rw.getId())
                            && log.getToStatus().equals(target)
                            && log.getOperatorName().equals(op)
                            && log.getReason().equals(rs)) {
                        return rw;
                    }
                    throw conflictRequestIdReuse();
                }
                // ② 乐观并发校验：结论已被他人变更 → 一致冲突提示，不静默覆盖
                if (!rw.getStatus().equals(from)) {
                    throw conflictStateChanged(rw.getStatus(), from);
                }
                // ③ 目标与当前结论相同：本次重判与已有裁决重复（连点 / 并发同目标）
                if (rw.getStatus().equals(target)) {
                    throw conflictDuplicateTarget(target);
                }
                String before = rw.getStatus();
                rw.setStatus(target);
                rw.setUpdatedAt(LocalDateTime.now());
                repository.save(rw);
                // saveAndFlush：request_id 唯一约束在事务内即刻校验，而不是拖到提交时
                addLog(rw.getId(), before, target, op, rs, reqId);
                return rw;
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                // 并发同 requestId：唯一索引兜底，复用已提交的那份裁决；参数不符则是标识被挪用
                return logRepository.findByRequestId(reqId)
                        .filter(l -> l.getRawWaterId().equals(id)
                                && l.getToStatus().equals(target)
                                && l.getOperatorName().equals(op)
                                && l.getReason().equals(rs))
                        .map(l -> get(id))
                        .orElseThrow(() -> conflictRequestIdReuse());
            }
            throw e;
        }
    }

    // ==================== 内部工具 ====================

    private RawWater lock(Long id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("原水监测记录不存在：" + id));
    }

    /** 越权防护：仅管理员可人工重判；角色缺失 / 无法确认身份同样明确拒绝 */
    private void requireAdmin(String role) {
        if (role == null || role.isBlank()) {
            throw new BizException("无法确认操作身份，人工重判仅管理员可执行");
        }
        if (!Dict.isAdmin(role.trim())) {
            throw new BizException("仅管理员可执行人工重判（当前角色：" + role.trim() + "）");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(message);
        }
        return value.trim();
    }

    private void checkLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new BizException(label + "不能超过 " + max + " 字");
        }
    }

    /** 一致的冲突提示：结论已被他人变更（基于过期页面 / 并发裁决） */
    private BizException conflictStateChanged(String current, String from) {
        return new BizException("裁决冲突：样本结论已被他人变更（当前「" + current
                + "」，提交时基于「" + from + "」），本次重判未执行，请刷新后重试");
    }

    /** 一致的冲突提示：当前结论已是目标值，与已有裁决重复 */
    private BizException conflictDuplicateTarget(String target) {
        return new BizException("裁决冲突：样本当前已是「" + target
                + "」，本次重判与已有裁决重复，未重复留痕，请刷新后重试");
    }

    /** 一致的冲突提示：请求标识被不同参数的裁决占用 */
    private BizException conflictRequestIdReuse() {
        return new BizException("裁决冲突：请求标识（requestId）已被其它裁决占用，请重新发起");
    }

    private void addLog(Long rawWaterId, String from, String to, String operator, String reason, String requestId) {
        RawWaterJudgmentLog log = new RawWaterJudgmentLog();
        log.setRawWaterId(rawWaterId);
        log.setAction(RawWaterJudgmentLog.ACTION_RECLASSIFY);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorName(operator);
        log.setReason(reason);
        log.setRequestId(requestId);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.saveAndFlush(log);
    }

    /** 唯一约束冲突识别：本流程中可能命中的只有 uk_rwjl_request 一个唯一键 */
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
}
