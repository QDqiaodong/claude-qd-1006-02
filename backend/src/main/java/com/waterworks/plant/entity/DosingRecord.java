package com.waterworks.plant.entity;

import java.math.BigDecimal;
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
 * 加药批次记录：运行工按投加批次登记，管理员核对回执后确认，异常批次复核退回。
 *
 * <p>状态机：待确认 → 已确认；待确认 → 异常待复核 → 已确认 / 已退回。</p>
 *
 * <p>chemName / targetSection 是登记时的快照：工艺配置事后调整不回写本表，
 * 由读取侧对比当前配置计算「配置已变更」标记。(targetSection, executeAt) 有唯一约束，
 * 同一目标工段同一执行时间只保留一个批次。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "dosing_record")
public class DosingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 药剂名（登记时必须在工艺配置启用项内） */
    @Column(name = "chem_name", nullable = false, length = 32)
    private String chemName;

    /** 投加量（mg/L） */
    @Column(name = "dose_mg_per_l", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMgPerL;

    /** 目标工段（登记时必须在工艺配置启用项内） */
    @Column(name = "target_section", nullable = false, length = 32)
    private String targetSection;

    /** 执行时间（与目标工段共同构成批次唯一槽位） */
    @Column(name = "execute_at", nullable = false)
    private LocalDateTime executeAt;

    /** 操作者（登记人） */
    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    @Column(name = "note", length = 128)
    private String note;

    /** 待确认 / 已确认 / 异常待复核 / 已退回 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 标记异常原因（管理员发现投加量 / 药剂 / 目标工段异常时填写） */
    @Column(name = "anomaly_reason", length = 255)
    private String anomalyReason;

    /** 复核结论（复核退回原因 / 复核通过说明） */
    @Column(name = "review_reason", length = 255)
    private String reviewReason;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
