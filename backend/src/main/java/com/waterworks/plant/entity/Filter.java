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
 * 滤池：过滤工段，分砂滤 / 炭滤。状态：运行 / 反洗。
 */
@Getter
@Setter
@Entity
@Table(name = "water_filter")
public class Filter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 滤池编号，唯一 */
    @Column(name = "filter_code", nullable = false, unique = true, length = 16)
    private String filterCode;

    /** 砂滤 / 炭滤 */
    @Column(name = "filter_type", nullable = false, length = 16)
    private String type;

    /** 滤速 / 流量（m³/h） */
    @Column(name = "flow_m3h", nullable = false, precision = 10, scale = 2)
    private BigDecimal flowM3h;

    /** 运行 / 反洗 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
