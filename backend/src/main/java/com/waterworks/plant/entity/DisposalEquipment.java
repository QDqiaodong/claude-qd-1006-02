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
 * 处置单-设备关联：处置单关联的沉淀池 / 滤池。
 *
 * <p>关联状态：正常 / 冲突 / 已排除 / 已改选。设备在处置期间离开「运行」（清洗 / 反洗）时，
 * 关联由设备流转的同事务钩子标为「冲突」并记录原因，绝不悄悄失效；
 * 值班人员必须改选可用设备或填写排除原因后才能继续。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "disposal_equipment")
public class DisposalEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    /** 沉淀池 / 滤池 */
    @Column(name = "equip_type", nullable = false, length = 8)
    private String equipType;

    /** 设备 id（sedimentation_tank.id 或 water_filter.id） */
    @Column(name = "equip_id", nullable = false)
    private Long equipId;

    /** 设备编号（冗余，如 SED-01 / F-01，便于展示与留痕） */
    @Column(name = "equip_code", nullable = false, length = 16)
    private String equipCode;

    /** 正常 / 冲突 / 已排除 / 已改选 */
    @Column(name = "link_status", nullable = false, length = 8)
    private String linkStatus;

    /** 冲突原因（如「设备已转入清洗」） */
    @Column(name = "conflict_reason", length = 128)
    private String conflictReason;

    /** 排除原因（值班人员手动排除时必填） */
    @Column(name = "exclude_reason", length = 128)
    private String excludeReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
