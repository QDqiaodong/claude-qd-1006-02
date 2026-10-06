package com.waterworks.plant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * 原水人工重判裁决日志：每次管理员重判追加一行，只增不改，逐次独立追溯。
 *
 * <p>{@code request_id} 带唯一索引：同一请求（快速连点 / 网络重试 / 重放）只会落一份日志，
 * 并发下同 request_id 的第二个 INSERT 必然唯一冲突，由后端转成幂等复用或明确冲突提示。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "raw_water_judgment_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_rwjl_request", columnNames = "request_id"))
public class RawWaterJudgmentLog {

    /** 动作：人工重判 */
    public static final String ACTION_RECLASSIFY = "人工重判";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被重判的原水监测记录 id */
    @Column(name = "raw_water_id", nullable = false)
    private Long rawWaterId;

    /** 动作：人工重判 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 重判前结论 */
    @Column(name = "from_status", length = 16)
    private String fromStatus;

    /** 重判后结论 */
    @Column(name = "to_status", nullable = false, length = 16)
    private String toStatus;

    /** 操作人（管理员） */
    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    /** 操作原因（必填） */
    @Column(name = "reason", nullable = false)
    private String reason;

    /** 客户端请求标识（幂等键），唯一 */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
