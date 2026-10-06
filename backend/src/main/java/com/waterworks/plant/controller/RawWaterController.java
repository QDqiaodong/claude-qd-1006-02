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

    @PostMapping
    public ApiResponse<RawWater> create(@RequestBody RawWater input) {
        return ApiResponse.success("原水监测记录已建档", service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<RawWater> update(@PathVariable Long id, @RequestBody RawWater input) {
        return ApiResponse.success("已更新", service.update(id, input));
    }

    /** 管理员手动重判水质状态（含置「超标」复核留痕） */
    @PostMapping("/{id}/reclassify")
    public ApiResponse<RawWater> reclassify(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("水质状态已重判", service.reclassify(id, status));
    }
}
