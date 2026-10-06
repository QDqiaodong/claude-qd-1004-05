package com.citygas.network.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.citygas.network.entity.GasSupplyQuota;

import lombok.Getter;
import lombok.Setter;

/**
 * 配额行视图：版本快照 + 当前档案能力差异 + 能力校验结果。
 *
 * <p>{@code capacityM3}/{@code quotaM3} 为版本快照（提交后不再变化）；
 * {@code currentCapacityM3}/{@code currentStatus} 为当前档案值，
 * 二者不一致时在 {@code diffs} 中给出提示，但绝不改写已保存配额。</p>
 */
@Getter
@Setter
public class GasPlanQuotaVO {

    private Long id;
    private String stationType;
    private Long stationId;
    private String stationCode;
    private String stationName;

    /** 版本快照能力上限（m³/日） */
    private BigDecimal capacityM3;
    /** 版本快照配额（m³/日） */
    private BigDecimal quotaM3;
    private String remark;

    /** 当前档案（可能已变） */
    private Boolean present;
    private String currentStationName;
    private String currentStatus;
    private BigDecimal currentCapacityM3;

    /** 配额为负 / 超过版本快照能力上限 */
    private boolean negative;
    private boolean overCapacity;

    /** CAPACITY_CHANGED / NAME_CHANGED / DISABLED / DELETED / CROSS_TENANT */
    private List<DiffItem> diffs = new ArrayList<>();

    @Getter
    @Setter
    public static class DiffItem {
        private String level;
        private String kind;
        private String message;

        public DiffItem() {
        }

        public DiffItem(String level, String kind, String message) {
            this.level = level;
            this.kind = kind;
            this.message = message;
        }
    }

    public static GasPlanQuotaVO of(GasSupplyQuota q) {
        GasPlanQuotaVO vo = new GasPlanQuotaVO();
        vo.id = q.getId();
        vo.stationType = q.getStationType();
        vo.stationId = q.getStationId();
        vo.stationCode = q.getStationCode();
        vo.stationName = q.getStationName();
        vo.capacityM3 = q.getCapacityM3();
        vo.quotaM3 = q.getQuotaM3();
        vo.remark = q.getRemark();
        vo.negative = q.isNegative();
        vo.overCapacity = q.isOverCapacity();
        return vo;
    }
}
