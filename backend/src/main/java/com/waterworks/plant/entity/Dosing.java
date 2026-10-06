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
 * 加药：向工艺链投加药剂（混凝剂 / 消毒剂）。
 *
 * <p>chemName 限定为 聚合氯化铝 / 次氯酸钠；doseMgPerL 为投加量（mg/L）。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "dosing")
public class Dosing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 药剂名：聚合氯化铝 / 次氯酸钠 */
    @Column(name = "chem_name", nullable = false, length = 32)
    private String chemName;

    /** 投加量（mg/L） */
    @Column(name = "dose_mg_per_l", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMgPerL;

    /** 目标池 / 目标工段，如 沉淀池-1 / 清水池 */
    @Column(name = "target_tank", nullable = false, length = 32)
    private String targetTank;

    /** 投加时间 */
    @Column(name = "dosed_at")
    private LocalDateTime dosedAt;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
