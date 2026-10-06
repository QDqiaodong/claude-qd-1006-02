package com.waterworks.plant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 工艺配置项：当前有效的 药剂 / 目标工段。
 *
 * <p>加药登记时药剂与目标工段必须在启用项内；停用某项后历史记录不受影响，
 * 仅在展示时标出「配置已变更」。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "process_config")
public class ProcessConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 药剂 / 目标工段 */
    @Column(name = "item_type", nullable = false, length = 8)
    private String itemType;

    @Column(name = "item_name", nullable = false, length = 32)
    private String itemName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
