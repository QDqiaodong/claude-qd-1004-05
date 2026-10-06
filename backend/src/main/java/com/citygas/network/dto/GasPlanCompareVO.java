package com.citygas.network.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 两个计划版本的配额差异对比：用于版本对比页面。
 *
 * <p>按「站点类型 + 站点 ID」对齐同站配额，输出新增 / 删除 / 变更行，
 * 并汇总门站、调压站配额合计差异与状态/需求差异。已冻结版本与新版本可直接比对。</p>
 */
@Getter
@Setter
public class GasPlanCompareVO {

    private Long fromPlanId;
    private Long toPlanId;
    private String fromPlanNo;
    private String toPlanNo;
    private Integer fromVersion;
    private Integer toVersion;
    private String fromStatus;
    private String toStatus;

    /** 片区日需求量差异 */
    private BigDecimal fromDemand;
    private BigDecimal toDemand;
    private BigDecimal demandDelta;

    private BigDecimal fromGateTotal;
    private BigDecimal toGateTotal;
    private BigDecimal gateTotalDelta;

    private BigDecimal fromRegulatorTotal;
    private BigDecimal toRegulatorTotal;
    private BigDecimal regulatorTotalDelta;

    private List<QuotaDiff> quotaDiffs;

    @Getter
    @Setter
    public static class QuotaDiff {
        private String stationType;
        private Long stationId;
        private String stationCode;
        private String stationName;
        /** ADDED 新版本新增 / REMOVED 新版本删除 / CHANGED 配额变化 / SAME 无变化 */
        private String changeType;
        private BigDecimal fromQuota;
        private BigDecimal toQuota;
        private BigDecimal quotaDelta;
        private BigDecimal fromCapacity;
        private BigDecimal toCapacity;
    }
}
