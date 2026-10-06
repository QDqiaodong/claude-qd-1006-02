package com.waterworks.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 处置单详情：单据本体 + 设备关联（含设备当前状态与冲突原因）+ 加药登记 + 完整流转记录。
 */
@Getter
@Setter
public class DisposalDetail {

    private Long id;
    private Long rawWaterId;
    private String sourceName;
    private String status;
    private String owner;
    private String conclusion;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    private List<EquipLink> equipments;
    private List<DosingItem> dosings;
    private List<LogItem> logs;

    /** 设备关联视图：关联状态 + 设备当前实时状态 + 冲突 / 排除原因 */
    @Getter
    @Setter
    public static class EquipLink {
        private Long linkId;
        private String equipType;
        private Long equipId;
        private String equipCode;
        private String linkStatus;
        private String currentEquipStatus;
        private String conflictReason;
        private String excludeReason;
        private LocalDateTime createdAt;
    }

    /** 加药登记视图 */
    @Getter
    @Setter
    public static class DosingItem {
        private Long id;
        private Long dosingId;
        private String chemName;
        private BigDecimal doseMgPerL;
        private String targetTank;
        private String operatorName;
        private LocalDateTime createdAt;
    }

    /** 流转记录视图 */
    @Getter
    @Setter
    public static class LogItem {
        private Long id;
        private String action;
        private String fromStatus;
        private String toStatus;
        private String operatorName;
        private String detail;
        private LocalDateTime createdAt;
    }
}
