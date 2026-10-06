package com.waterworks.plant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.entity.RawWaterJudgmentLog;
import com.waterworks.plant.service.RawWaterService;

/**
 * 原水监测接口。
 *
 * <p>人工重判携带 role / operator / reason / requestId，后端按与页面按钮一致的口径
 * 重新校验：仅管理员可重判，必须填写操作原因，同一请求重放幂等复用首次裁决结果。</p>
 */
@RestController
@RequestMapping("/api/raw-waters")
public class RawWaterController {

    private final RawWaterService service;

    public RawWaterController(RawWaterService service) {
        this.service = service;
    }

    /**
     * 人工重判请求体：目标结论 + 提交时所见结论（乐观并发校验）+ 操作人 + 角色 +
     * 操作原因（必填）+ 请求标识（幂等键）。
     */
    public record ReclassifyReq(String status, String fromStatus, String operator,
                                String role, String reason, String requestId) {
    }

    @GetMapping
    public ApiResponse<List<RawWater>> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String sourceName) {
        return ApiResponse.success(service.list(status, sourceName));
    }

    @GetMapping("/{id}")
    public ApiResponse<RawWater> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    /** 某条原水记录的裁决日志（只增不改，按入库顺序） */
    @GetMapping("/{id}/judgment-logs")
    public ApiResponse<List<RawWaterJudgmentLog>> judgmentLogs(@PathVariable Long id) {
        return ApiResponse.success(service.judgmentLogs(id));
    }

    @PostMapping
    public ApiResponse<RawWater> create(@RequestBody RawWater input) {
        return ApiResponse.success("原水监测记录已建档", service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<RawWater> update(@PathVariable Long id, @RequestBody RawWater input) {
        return ApiResponse.success("已更新", service.update(id, input));
    }

    /** 管理员人工重判水质结论：必须填写操作原因，每次成功重判追加一份不可覆盖的裁决日志 */
    @PostMapping("/{id}/reclassify")
    public ApiResponse<RawWater> reclassify(@PathVariable Long id, @RequestBody ReclassifyReq req) {
        return ApiResponse.success("水质状态已重判",
                service.reclassify(id, req.status(), req.fromStatus(), req.operator(),
                        req.role(), req.reason(), req.requestId()));
    }
}
