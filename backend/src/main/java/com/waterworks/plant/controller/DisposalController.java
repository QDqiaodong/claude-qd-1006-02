package com.waterworks.plant.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.dto.DisposalDetail;
import com.waterworks.plant.dto.DisposalSummary;
import com.waterworks.plant.service.DisposalService;

/**
 * 原水超标联动处置接口：待处置 → 执行中 → 已解除 闭环。
 */
@RestController
@RequestMapping("/api/disposals")
public class DisposalController {

    private final DisposalService service;

    public DisposalController(DisposalService service) {
        this.service = service;
    }

    // ---------------- 请求体 ----------------

    /** 发起处置：原水记录 + 责任人 + 运行中的沉淀池 / 滤池选择 */
    public record InitiateReq(Long rawWaterId, String owner, List<Long> tankIds, List<Long> filterIds) {
    }

    public record OperatorReq(String operator) {
    }

    public record ResolveReq(String conclusion, String operator) {
    }

    public record ReplaceReq(Long newEquipId, String operator) {
    }

    public record ExcludeReq(String reason, String operator) {
    }

    public record DosingReq(String chemName, BigDecimal doseMgPerL, String targetTank, String operator) {
    }

    // ---------------- 查询 ----------------

    @GetMapping
    public ApiResponse<List<DisposalSummary>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<DisposalDetail> get(@PathVariable Long id) {
        return ApiResponse.success(service.getDetail(id));
    }

    // ---------------- 闭环操作 ----------------

    /** 发起处置：同一水源同一时刻只允许一条未解除，并发第二单返回「已被接管」 */
    @PostMapping
    public ApiResponse<DisposalDetail> initiate(@RequestBody InitiateReq req) {
        return ApiResponse.success("处置单已发起",
                service.initiate(req.rawWaterId(), req.owner(), req.tankIds(), req.filterIds()));
    }

    /** 待处置 → 执行中：需至少一台正常关联的沉淀池和一台滤池，且无未处理冲突 */
    @PostMapping("/{id}/start")
    public ApiResponse<DisposalDetail> start(@PathVariable Long id, @RequestBody OperatorReq req) {
        return ApiResponse.success("已进入执行中", service.start(id, req.operator()));
    }

    /** 执行中 → 已解除：单事务校验冲突并写入结论与时间，失败整体回滚 */
    @PostMapping("/{id}/resolve")
    public ApiResponse<DisposalDetail> resolve(@PathVariable Long id, @RequestBody ResolveReq req) {
        return ApiResponse.success("处置已解除", service.resolve(id, req.conclusion(), req.operator()));
    }

    /** 冲突改选：换一台「运行」中的同类型设备 */
    @PostMapping("/{id}/equipments/{linkId}/replace")
    public ApiResponse<DisposalDetail> replace(@PathVariable Long id, @PathVariable Long linkId,
                                               @RequestBody ReplaceReq req) {
        return ApiResponse.success("已改选设备", service.replaceEquipment(id, linkId, req.newEquipId(), req.operator()));
    }

    /** 冲突排除：填写排除原因 */
    @PostMapping("/{id}/equipments/{linkId}/exclude")
    public ApiResponse<DisposalDetail> exclude(@PathVariable Long id, @PathVariable Long linkId,
                                               @RequestBody ExcludeReq req) {
        return ApiResponse.success("已排除设备", service.excludeEquipment(id, linkId, req.reason(), req.operator()));
    }

    /** 登记加药：幂等，同一加药动作重复提交不会产生两份记录 */
    @PostMapping("/{id}/dosings")
    public ApiResponse<DisposalDetail.DosingItem> registerDosing(@PathVariable Long id, @RequestBody DosingReq req) {
        return ApiResponse.success("加药已登记（重复提交自动去重）",
                service.registerDosing(id, req.chemName(), req.doseMgPerL(), req.targetTank(), req.operator()));
    }
}
