package com.waterworks.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 加药批次列表行：登记快照 + 当前状态 + 复核信息 + 「配置已变更」标记。
 *
 * <p>chemOutOfConfig / targetOutOfConfig 为 true 表示登记后工艺配置已调整，
 * 历史记录仍展示登记时的值，不回写。</p>
 */
@Getter
@Setter
public class DosingRecordSummary {

    private Long id;
    private String chemName;
    private BigDecimal doseMgPerL;
    private String targetSection;
    private LocalDateTime executeAt;
    private String operatorName;
    private String note;
    private String status;
    private String anomalyReason;
    private String reviewReason;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private boolean chemOutOfConfig;
    private boolean targetOutOfConfig;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
