package com.waterworks.plant.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 处置单列表行：带关联工段统计与冲突计数，值班人员一眼看到责任人与风险。
 */
@Getter
@Setter
public class DisposalSummary {

    private Long id;
    private Long rawWaterId;
    private String sourceName;
    private String status;
    private String owner;
    private int tankCount;
    private int filterCount;
    private int conflictCount;
    private int dosingCount;
    private String conclusion;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
