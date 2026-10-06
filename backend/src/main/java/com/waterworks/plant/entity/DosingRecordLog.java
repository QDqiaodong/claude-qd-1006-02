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
 * 加药批次操作留痕：登记 / 修改 / 确认 / 标记异常 / 复核通过 / 复核退回。
 */
@Getter
@Setter
@Entity
@Table(name = "dosing_record_log")
public class DosingRecordLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator_name", length = 32)
    private String operatorName;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
