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
import com.waterworks.plant.dto.RawWaterDetail;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.service.RawWaterService;

/** 原水监测接口 */
@RestController
@RequestMapping("/api/raw-waters")
public class RawWaterController {

    private final RawWaterService service;

    public RawWaterController(RawWaterService service) {
        this.service = service;
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

    /** 某样本的人工重判裁决链：按发生顺序返回，每次裁决独立可追溯 */
    @GetMapping("/{id}/reclassify-logs")
    public ApiResponse<RawWaterDetail> logs(@PathVariable Long id) {
        return ApiResponse.success(service.getDetail(id));
    }

    @PostMapping
    public ApiResponse<RawWater> create(@RequestBody RawWater input) {
        return ApiResponse.success("原水监测记录已建档", service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<RawWater> update(@PathVariable Long id, @RequestBody RawWater input) {
        return ApiResponse.success("已更新", service.update(id, input));
    }

    /** 人工重判请求体：角色 / 操作者 / 操作原因必填，重判前结论用于并发冲突检测，requestId 用于请求幂等 */
    public record ReclassifyReq(String status, String operator, String reason,
                                String role, String expectedStatus, String requestId) {
    }

    /**
     * 管理员手动重判水质状态（含置「超标」复核留痕）。
     * 仅管理员可发起，必须填写操作原因；重放安全复用，并发裁决冲突时返回一致提示。
     */
    @PostMapping("/{id}/reclassify")
    public ApiResponse<RawWaterDetail> reclassify(@PathVariable Long id, @RequestBody ReclassifyReq req) {
        return ApiResponse.success("水质状态已重判",
                service.reclassify(id, req.status(), req.operator(), req.reason(),
                        req.role(), req.expectedStatus(), req.requestId()));
    }
}
