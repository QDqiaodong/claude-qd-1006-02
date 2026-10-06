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
 * 沉淀池：取水后的沉淀工段。状态：运行 / 清洗。
 */
@Getter
@Setter
@Entity
@Table(name = "sedimentation_tank")
public class SedimentationTank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 池编号，唯一 */
    @Column(name = "tank_code", nullable = false, unique = true, length = 16)
    private String tankCode;

    /** 有效容积（m³） */
    @Column(name = "capacity_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal capacityM3;

    /** 停留时间（分钟），必须大于 0 */
    @Column(name = "retention_min", nullable = false)
    private Integer retentionMin;

    /** 运行 / 清洗 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
