package com.waterworks.plant.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 原水样本详情：样本本体 + 完整人工重判裁决链。
 *
 * <p>列表接口仍直接返回实体；详情接口附带只增不改的裁决日志，供页面与审计追溯。</p>
 */
@Getter
@Setter
public class RawWaterDetail {

    private Long id;
    private String sourceName;
    private LocalDateTime sampleAt;
    private String ph;
    private String turbidityNtu;
    private String chlorineMgL;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ReclassifyLogItem> logs;

    /** 裁决日志视图：一次裁决一条 */
    @Getter
    @Setter
    public static class ReclassifyLogItem {
        private Long id;
        private String fromStatus;
        private String toStatus;
        private String operatorName;
        private String reason;
        private String requestId;
        private LocalDateTime createdAt;
    }
}
