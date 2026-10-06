package com.waterworks.plant.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.dto.DosingRecordDetail;
import com.waterworks.plant.dto.DosingRecordSummary;
import com.waterworks.plant.service.DosingRecordService;

/**
 * 加药批次记录与异常复核接口：待确认 → 已确认；待确认 → 异常待复核 → 已确认 / 已退回。
 *
 * <p>所有写操作都携带 role 与 operator，后端按与页面按钮一致的口径重新校验。</p>
 */
@RestController
@RequestMapping("/api/dosing-records")
public class DosingRecordController {

    private final DosingRecordService service;

    public DosingRecordController(DosingRecordService service) {
        this.service = service;
    }

    // ---------------- 请求体 ----------------

    /** 登记 / 修改：五项必填（药剂、投加量、目标工段、执行时间、操作者）+ 备注 + 角色 */
    public record SaveReq(String chemName, BigDecimal doseMgPerL, String targetSection,
                          LocalDateTime executeAt, String operator, String note, String role) {
    }

    public record ConfirmReq(String operator, String role, String note) {
    }

    public record ReasonReq(String operator, String role, String reason) {
    }

    // ---------------- 查询 ----------------

    /** 列表：按目标工段 / 状态 / 执行时间区间追溯 */
    @GetMapping
    public ApiResponse<List<DosingRecordSummary>> list(
            @RequestParam(required = false) String targetSection,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime executeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime executeTo) {
        return ApiResponse.success(service.list(targetSection, status, executeFrom, executeTo));
    }

    /** 全部异常（异常待复核 + 已退回）：仅管理员 */
    @GetMapping("/anomalies")
    public ApiResponse<List<DosingRecordSummary>> anomalies(@RequestParam(required = false) String role) {
        return ApiResponse.success(service.anomalies(role));
    }

    @GetMapping("/{id}")
    public ApiResponse<DosingRecordDetail> get(@PathVariable Long id) {
        return ApiResponse.success(service.getDetail(id));
    }

    // ---------------- 登记与维护 ----------------

    /** 登记批次：同一目标工段同一执行时间只保留一个批次，重复提交返回已存在记录提示 */
    @PostMapping
    public ApiResponse<DosingRecordDetail> create(@RequestBody SaveReq req) {
        return ApiResponse.success("加药批次已登记，待管理员核对回执确认",
                service.create(req.chemName(), req.doseMgPerL(), req.targetSection(),
                        req.executeAt(), req.operator(), req.note(), req.role()));
    }

    /** 修改：仅登记人本人且记录为「待确认」时可改 */
    @PutMapping("/{id}")
    public ApiResponse<DosingRecordDetail> update(@PathVariable Long id, @RequestBody SaveReq req) {
        return ApiResponse.success("登记内容已更新",
                service.update(id, req.chemName(), req.doseMgPerL(), req.targetSection(),
                        req.executeAt(), req.operator(), req.note(), req.role()));
    }

    // ---------------- 确认与复核（仅管理员） ----------------

    /** 确认：待确认 → 已确认（核对现场回执）；异常待复核 → 已确认（复核通过） */
    @PostMapping("/{id}/confirm")
    public ApiResponse<DosingRecordDetail> confirm(@PathVariable Long id, @RequestBody ConfirmReq req) {
        return ApiResponse.success("已确认为有效批次",
                service.confirm(id, req.operator(), req.role(), req.note()));
    }

    /** 标记异常：待确认 → 异常待复核，必须说明原因 */
    @PostMapping("/{id}/mark-anomaly")
    public ApiResponse<DosingRecordDetail> markAnomaly(@PathVariable Long id, @RequestBody ReasonReq req) {
        return ApiResponse.success("已标记为异常待复核",
                service.markAnomaly(id, req.operator(), req.role(), req.reason()));
    }

    /** 复核退回：异常待复核 → 已退回，保留原始登记并记录原因 */
    @PostMapping("/{id}/review-return")
    public ApiResponse<DosingRecordDetail> reviewReturn(@PathVariable Long id, @RequestBody ReasonReq req) {
        return ApiResponse.success("已复核退回，原始登记保留",
                service.reviewReturn(id, req.operator(), req.role(), req.reason()));
    }
}
