package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.ConflictException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.common.ForbiddenException;
import com.waterworks.plant.dto.RawWaterDetail;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.entity.RawWaterReclassifyLog;
import com.waterworks.plant.repository.RawWaterReclassifyLogRepository;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.type.WaterSample;

/**
 * 原水监测业务。
 *
 * <p>核心规则：① 建档时 pH 必须 6.5~8.5、浊度 < 3 NTU、余氯 0.3~4 mg/L，否则拒绝建档；
 * ② 通过硬校验后按软预警带自动判「合格 / 预警」；③ 人工重判仅管理员可发起，且必须填写操作原因。</p>
 *
 * <p>人工重判的权限与一致性全部在后端保证，与页面按钮同一口径：</p>
 * <ul>
 *   <li>权限一致 —— 只有管理员能对已判定样本发起人工重判；普通角色与身份不可确认（role 缺失）
 *       的访问一律 403 明确拒绝，样本结论、更新时间、已有裁决日志均不变化。</li>
 *   <li>裁决留痕 —— 每次裁决追加一条只增不改的日志，完整记录操作者、操作原因、操作时间以及
 *       重判前后结论；后续每次裁决都能独立追溯，应用层不提供日志更新 / 删除入口。</li>
 *   <li>请求幂等 —— 客户端为每次裁决生成 requestId（唯一索引兜底）。同一请求重放 / 快速连点
 *       只产生一条日志、一次结论变更；参数一致时安全复用首次结果，参数不一致时返回一致的冲突提示。</li>
 *   <li>并发裁决 —— 事务内对样本行加悲观写锁串行化；后到者基于先到者的新结论判断：
 *       携带的重判前结论（expectedStatus）与当前结论不符时 409 拒绝，禁止后到操作静默覆盖先到裁决，
 *       也不会出现最终结论与日志顺序矛盾。</li>
 * </ul>
 */
@Service
public class RawWaterService {

    private final RawWaterRepository repository;
    private final RawWaterReclassifyLogRepository logRepository;
    private final TransactionTemplate txTemplate;

    public RawWaterService(RawWaterRepository repository,
                           RawWaterReclassifyLogRepository logRepository,
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

    /** 详情：样本本体 + 完整裁决日志（只读，不改变任何数据） */
    public RawWaterDetail getDetail(Long id) {
        return toDetail(get(id));
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
        LocalDateTime now = LocalDateTime.now();
        RawWater rw = new RawWater();
        rw.setSourceName(input.getSourceName().trim());
        rw.setWaterSample(ws);
        rw.setSampleAt(input.getSampleAt() == null ? now : input.getSampleAt());
        // 规则②：自动判级
        rw.setStatus(Dict.classifyRawWater(ws));
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
        return repository.save(rw);
    }

    // ==================== 人工重判（仅管理员） ====================

    /**
     * 管理员手动重判水质状态。
     *
     * @param id             样本 id
     * @param status         重判后结论（合格 / 预警 / 超标）
     * @param operator       操作者姓名（必填）
     * @param reason         操作原因（必填）
     * @param role           发起者角色：仅「管理员」放行；其余角色与 null / 空白（身份不可确认）一律拒绝
     * @param expectedStatus 发起方看到的重判前结论；与库中当前结论不符说明已被并发裁决先改变 → 409
     * @param requestId      客户端为本次裁决生成的幂等令牌；同令牌重放只产生一条日志
     */
    public RawWaterDetail reclassify(Long id, String status, String operator, String reason,
                                     String role, String expectedStatus, String requestId) {
        // ① 权限：只有管理员。普通角色、身份缺失 / 不可确认都在此处明确拒绝，不触碰任何数据
        if (!Dict.isAdmin(role)) {
            throw new ForbiddenException("仅管理员可对已判定样本发起人工重判，当前请求已被拒绝");
        }
        String op = requireText(operator, "操作者不能为空");
        String rs = requireText(reason, "人工重判必须填写操作原因");
        if (rs.length() > 255) {
            throw new BizException("操作原因不能超过 255 字");
        }
        if (!Dict.isValidRawWaterStatus(status)) {
            throw new BizException("水质状态不合法：" + status);
        }
        String reqId = requestId == null || requestId.isBlank() ? null : requestId.trim();
        if (reqId != null && reqId.length() > 64) {
            throw new BizException("请求令牌不能超过 64 字");
        }
        String expected = expectedStatus == null || expectedStatus.isBlank() ? null : expectedStatus.trim();
        if (expected != null && !Dict.isValidRawWaterStatus(expected)) {
            throw new BizException("重判前结论不合法：" + expected);
        }

        // ② 重放预检：同令牌已有裁决 → 不重复留痕。参数一致安全复用，不一致给一致的冲突提示
        if (reqId != null) {
            RawWaterReclassifyLog existed = logRepository.findByRequestId(reqId).orElse(null);
            if (existed != null) {
                return replayResult(existed, id, status, op, rs, expected);
            }
        }

        // ③ 行锁内串行裁决。锁内复查幂等槽（应对两个并发请求同时通过预检的竞态）
        Long logId;
        try {
            logId = txTemplate.execute(tx -> {
                // 先锁样本行：与并发裁决 / 重放串行化，拿到锁时先到者必然已提交
                RawWater rw = repository.findByIdForUpdate(id)
                        .orElseThrow(() -> new BizException("原水监测记录不存在：" + id));
                // 锁内复查幂等槽：拿到锁后先到者的裁决必然可见，重放安全复用，绝不误判为冲突
                RawWaterReclassifyLog inside;
                if (reqId != null && (inside = logRepository.findByRequestId(reqId).orElse(null)) != null) {
                    return inside.getId();
                }
                String from = rw.getStatus();
                // 并发冲突：发起方持有的重判前结论已被先到者改变，禁止静默覆盖
                if (expected != null && !expected.equals(from)) {
                    throw new ConflictException("样本「" + rw.getSourceName() + "」的结论已被其他裁决改为「"
                            + from + "」，与您重判前看到的「" + expected
                            + "」不一致；请刷新结论后重新发起重判");
                }
                // 结论未变化：不产生裁决、不留痕（连点同一目标在第二击直接得到明确提示）
                if (status.equals(from)) {
                    throw new ConflictException("样本「" + rw.getSourceName() + "」当前结论已是「" + from
                            + "」，无需重复裁决");
                }
                LocalDateTime now = LocalDateTime.now();
                rw.setStatus(status);
                rw.setUpdatedAt(now);
                repository.saveAndFlush(rw);

                RawWaterReclassifyLog log = new RawWaterReclassifyLog();
                log.setRawWaterId(id);
                log.setFromStatus(from);
                log.setToStatus(status);
                log.setOperatorName(op);
                log.setReason(rs);
                log.setRequestId(reqId);
                log.setCreatedAt(now);
                // saveAndFlush：唯一索引在事务内即刻校验，重放竞态由数据库兜底，不会落第二条
                logRepository.saveAndFlush(log);
                return log.getId();
            });
        } catch (RuntimeException e) {
            if (reqId != null && isUniqueViolation(e)) {
                // 并发兜底：同 requestId 的另一请求已抢先提交 → 复用其结果或按冲突处理
                RawWaterReclassifyLog winner = logRepository.findByRequestId(reqId).orElseThrow(() -> e);
                return replayResult(winner, id, status, op, rs, expected);
            }
            throw e;
        }
        return getDetail(logRepository.findById(logId).orElseThrow().getRawWaterId());
    }

    /**
     * 重放命中已存在裁决时的统一口径：
     * 参数与首次裁决一致 → 安全复用同一结果（多次重放看到的结论 / 日志完全相同）；
     * 参数不一致（换目标样本 / 换重判后结论 / 换操作者 / 换原因）→ 409 一致冲突提示，绝不改写原裁决。
     */
    private RawWaterDetail replayResult(RawWaterReclassifyLog existed, Long id, String status,
                                        String operator, String reason, String expected) {
        boolean same = existed.getRawWaterId().equals(id)
                && existed.getToStatus().equals(status)
                && existed.getOperatorName().equals(operator)
                && existed.getReason().equals(reason)
                && (expected == null || expected.equals(existed.getFromStatus()));
        if (!same) {
            throw new ConflictException("该请求已完成过一次裁决（样本 #" + existed.getRawWaterId()
                    + "：" + existed.getFromStatus() + " → " + existed.getToStatus()
                    + "），重复请求的参数与首次裁决不一致，已拒绝，请使用新请求发起新的裁决");
        }
        return getDetail(existed.getRawWaterId());
    }

    // ==================== 内部工具 ====================

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(message);
        }
        return value.trim();
    }

    /** 唯一约束冲突识别：本流程中可能命中的只有 uk_rwrl_request_id 一个唯一键 */
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

    private RawWaterDetail toDetail(RawWater rw) {
        RawWaterDetail d = new RawWaterDetail();
        d.setId(rw.getId());
        d.setSourceName(rw.getSourceName());
        d.setSampleAt(rw.getSampleAt());
        WaterSample ws = rw.getWaterSample();
        if (ws != null) {
            d.setPh(ws.getPh() == null ? null : ws.getPh().toPlainString());
            d.setTurbidityNtu(ws.getTurbidityNtu() == null ? null : ws.getTurbidityNtu().toPlainString());
            d.setChlorineMgL(ws.getChlorineMgL() == null ? null : ws.getChlorineMgL().toPlainString());
        }
        d.setStatus(rw.getStatus());
        d.setCreatedAt(rw.getCreatedAt());
        d.setUpdatedAt(rw.getUpdatedAt());
        d.setLogs(logRepository.findByRawWaterIdOrderById(rw.getId()).stream().map(l -> {
            RawWaterDetail.ReclassifyLogItem item = new RawWaterDetail.ReclassifyLogItem();
            item.setId(l.getId());
            item.setFromStatus(l.getFromStatus());
            item.setToStatus(l.getToStatus());
            item.setOperatorName(l.getOperatorName());
            item.setReason(l.getReason());
            item.setRequestId(l.getRequestId());
            item.setCreatedAt(l.getCreatedAt());
            return item;
        }).toList());
        return d;
    }
}
