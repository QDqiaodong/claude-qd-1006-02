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
 * 处置流转记录：处置单全生命周期的留痕（发起 / 进入执行 / 登记加药 /
 * 设备冲突 / 改选设备 / 排除设备 / 解除）。
 */
@Getter
@Setter
@Entity
@Table(name = "disposal_log")
public class DisposalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    /** 动作：发起 / 进入执行 / 登记加药 / 设备冲突 / 改选设备 / 排除设备 / 解除 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    /** 操作人（系统钩子记为「系统」） */
    @Column(name = "operator_name", length = 32)
    private String operatorName;

    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
