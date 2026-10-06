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
 * 超标处置单：从一条判为「超标」的原水监测记录发起的联动处置闭环。
 *
 * <p>状态机：待处置 → 执行中 → 已解除（仅顺向流转）。
 * 数据库生成列 {@code active_source}（未解除时 = source_name，解除后为 NULL）配合唯一索引，
 * 保证每个水源同一时刻最多一条未解除处置 —— 并发发起的第二单会在 INSERT 时唯一冲突。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "disposal")
public class Disposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起依据的原水监测记录 id */
    @Column(name = "raw_water_id", nullable = false)
    private Long rawWaterId;

    /** 水源 / 取水口名称（冗余自原水记录，用于唯一槽位与展示） */
    @Column(name = "source_name", nullable = false, length = 32)
    private String sourceName;

    /** 待处置 / 执行中 / 已解除 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 当前责任人（值班人员） */
    @Column(name = "owner", nullable = false, length = 32)
    private String owner;

    /** 处理结论（解除时必填） */
    @Column(name = "conclusion")
    private String conclusion;

    /** 解除时间 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
