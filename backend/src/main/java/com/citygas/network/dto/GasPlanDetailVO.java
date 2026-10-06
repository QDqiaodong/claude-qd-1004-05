package com.citygas.network.dto;

import java.math.BigDecimal;
import java.util.List;

import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.GasSupplyPlanHistory;

import lombok.Getter;
import lombok.Setter;

/**
 * 供气计划详情：版本主信息 + 门站/调压站配额（含能力校验与档案差异）+ 流转/复核记录。
 *
 * <p>校验提示分两级：{@code blockCount} 为提交拦截项（缺需求来源、负配额、超进站/出口能力、
 * 站点缺失），{@code warnCount} 为仅提示项（配额合计小于片区需求、档案停用等）。
 * 已冻结版本读取时同样返回全部历史分配与复核意见。</p>
 */
@Getter
@Setter
public class GasPlanDetailVO {

    private GasSupplyPlan plan;
    private List<GasPlanQuotaVO> gateQuotas;
    private List<GasPlanQuotaVO> regulatorQuotas;
    private List<HistoryVO> histories;

    /** 门站配额合计 / 门站进站能力合计 */
    private BigDecimal gateQuotaTotal = BigDecimal.ZERO;
    private BigDecimal gateCapacityTotal = BigDecimal.ZERO;
    /** 调压站配额合计 / 调压站出口能力合计 */
    private BigDecimal regulatorQuotaTotal = BigDecimal.ZERO;
    private BigDecimal regulatorCapacityTotal = BigDecimal.ZERO;

    private long blockCount;
    private long warnCount;

    /** 流转与复核意见记录 */
    @Getter
    @Setter
    public static class HistoryVO {
        private Long id;
        private String action;
        private String fromStatus;
        private String toStatus;
        private String operatorName;
        private String reviewOpinion;
        private String createdAt;

        public static HistoryVO of(GasSupplyPlanHistory h) {
            HistoryVO vo = new HistoryVO();
            vo.id = h.getId();
            vo.action = h.getAction();
            vo.fromStatus = h.getFromStatus();
            vo.toStatus = h.getToStatus();
            vo.operatorName = h.getOperatorName();
            vo.reviewOpinion = h.getReviewOpinion();
            vo.createdAt = h.getCreatedAt() == null ? null : h.getCreatedAt().toString();
            return vo;
        }
    }
}
