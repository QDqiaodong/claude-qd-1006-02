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
import com.waterworks.plant.entity.Filter;
import com.waterworks.plant.service.FilterService;

/** 滤池接口 */
@RestController
@RequestMapping("/api/filters")
public class FilterController {

    private final FilterService service;

    public FilterController(FilterService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Filter>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String type) {
        return ApiResponse.success(service.list(status, type));
    }

    @GetMapping("/{id}")
    public ApiResponse<Filter> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Filter> create(@RequestBody Filter input) {
        return ApiResponse.success("滤池已建档", service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Filter> update(@PathVariable Long id, @RequestBody Filter input) {
        return ApiResponse.success("已更新", service.update(id, input));
    }

    /** 反洗：置状态为「反洗」 */
    @PostMapping("/{id}/backwash")
    public ApiResponse<Filter> backwash(@PathVariable Long id) {
        return ApiResponse.success("已下达反洗指令", service.backwash(id));
    }

    /** 反洗结束：转回「运行」 */
    @PostMapping("/{id}/resume")
    public ApiResponse<Filter> resume(@PathVariable Long id) {
        return ApiResponse.success("已恢复运行", service.resume(id));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<Filter> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", service.transfer(id, target));
    }
}
