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
 * 处置单-加药登记：本次处置调整的药剂、投加量与目标工段。
 *
 * <p>每条登记同时在 dosing 表落一行工段加药流水（{@link #dosingId} 回指）。
 * 表上唯一约束 (disposal_id, chem_name, target_tank) 保证同一加药动作只落一条：
 * 重复点击 / 重复提交命中唯一约束，后端按幂等成功返回已存在记录。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "disposal_dosing")
public class DisposalDosing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    /** 关联的加药流水 id（dosing.id） */
    @Column(name = "dosing_id", nullable = false)
    private Long dosingId;

    /** 药剂名：聚合氯化铝 / 次氯酸钠 */
    @Column(name = "chem_name", nullable = false, length = 32)
    private String chemName;

    /** 投加量（mg/L） */
    @Column(name = "dose_mg_per_l", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMgPerL;

    /** 目标工段，如 SED-01 / F-01 / 清水池 */
    @Column(name = "target_tank", nullable = false, length = 32)
    private String targetTank;

    /** 登记人 */
    @Column(name = "operator_name", length = 32)
    private String operatorName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
