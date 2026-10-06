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
 * 原水人工重判裁决日志：一次裁决一行，只增不改。
 *
 * <p>仅管理员可发起人工重判；每行完整记录样本、重判前后结论、操作者、操作原因与操作时间，
 * 使后续每次裁决都能独立追溯。应用层不提供更新 / 删除入口。</p>
 *
 * <p>{@code requestId} 是客户端生成的幂等令牌，数据库层有唯一索引兜底：
 * 同一请求的重放 / 快速连点只可能落一行日志；为 null（老协议）时不占幂等槽。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "raw_water_reclassify_log")
public class RawWaterReclassifyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被重判的原水样本 */
    @Column(name = "raw_water_id", nullable = false)
    private Long rawWaterId;

    /** 重判前结论：合格 / 预警 / 超标 */
    @Column(name = "from_status", nullable = false, length = 16)
    private String fromStatus;

    /** 重判后结论：合格 / 预警 / 超标 */
    @Column(name = "to_status", nullable = false, length = 16)
    private String toStatus;

    /** 操作者（管理员） */
    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    /** 操作原因，必填 */
    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    /** 请求幂等令牌：同一令牌重放只产生一条日志；null 表示请求未携带令牌 */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** 裁决时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
