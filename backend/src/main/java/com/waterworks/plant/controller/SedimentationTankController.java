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
import com.waterworks.plant.entity.SedimentationTank;
import com.waterworks.plant.service.SedimentationTankService;

/** 沉淀池接口 */
@RestController
@RequestMapping("/api/sedimentation-tanks")
public class SedimentationTankController {

    private final SedimentationTankService service;

    public SedimentationTankController(SedimentationTankService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<SedimentationTank>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<SedimentationTank> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<SedimentationTank> create(@RequestBody SedimentationTank input) {
        return ApiResponse.success("沉淀池已建档", service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<SedimentationTank> update(@PathVariable Long id, @RequestBody SedimentationTank input) {
        return ApiResponse.success("已更新", service.update(id, input));
    }

    /** 运行 ↔ 清洗 流转 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<SedimentationTank> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", service.transfer(id, target));
    }
}
