package com.waterworks.plant.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 加药批次详情：批次本体（含配置变更标记）+ 完整操作留痕。
 */
@Getter
@Setter
public class DosingRecordDetail extends DosingRecordSummary {

    private List<LogItem> logs;

    /** 操作留痕视图 */
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
