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
import com.waterworks.plant.entity.Dosing;
import com.waterworks.plant.service.DosingService;

/** 加药接口 */
@RestController
@RequestMapping("/api/dosings")
public class DosingController {

    private final DosingService service;

    public DosingController(DosingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Dosing>> list(@RequestParam(required = false) String chemName,
                                          @RequestParam(required = false) String targetTank) {
        return ApiResponse.success(service.list(chemName, targetTank));
    }

    @GetMapping("/{id}")
    public ApiResponse<Dosing> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Dosing> create(@RequestBody Dosing input) {
        return ApiResponse.success("加药已登记", service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Dosing> update(@PathVariable Long id, @RequestBody Dosing input) {
        return ApiResponse.success("已更新", service.update(id, input));
    }
}
