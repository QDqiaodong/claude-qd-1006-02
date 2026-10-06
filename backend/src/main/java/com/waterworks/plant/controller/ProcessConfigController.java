package com.waterworks.plant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.entity.ProcessConfig;
import com.waterworks.plant.service.ProcessConfigService;

/**
 * 工艺配置接口：当前有效的 药剂 / 目标工段。
 *
 * <p>查询对所有角色开放（登记表单需要引用配置项）；新增与启停仅管理员，
 * 后端按 role 重新校验，与页面按钮口径一致。</p>
 */
@RestController
@RequestMapping("/api/process-config")
public class ProcessConfigController {

    private final ProcessConfigService service;

    public ProcessConfigController(ProcessConfigService service) {
        this.service = service;
    }

    public record AddReq(String itemType, String itemName, String role) {
    }

    public record ToggleReq(boolean enabled, String role) {
    }

    @GetMapping
    public ApiResponse<List<ProcessConfig>> list(@RequestParam(required = false) String itemType) {
        return ApiResponse.success(service.list(itemType));
    }

    /** 新增配置项（仅管理员） */
    @PostMapping
    public ApiResponse<ProcessConfig> add(@RequestBody AddReq req) {
        return ApiResponse.success("配置项已添加", service.add(req.itemType(), req.itemName(), req.role()));
    }

    /** 启用 / 停用（仅管理员）：停用后新登记不可选，历史记录保留并标注「配置已变更」 */
    @PostMapping("/{id}/toggle")
    public ApiResponse<ProcessConfig> toggle(@PathVariable Long id, @RequestBody ToggleReq req) {
        return ApiResponse.success(req.enabled() ? "配置项已启用" : "配置项已停用",
                service.toggle(id, req.enabled(), req.role()));
    }
}
