package com.citygas.network.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 供气计划创建 / 草稿编辑入参。
 *
 * <p>仅「草稿」可编辑；配额按门站/调压站拆分，提交时逐行过能力约束：
 * 门站配额合计不得超过进站能力、调压站配额不得超过出口上限、配额不得为负。</p>
 */
@Getter
@Setter
public class GasPlanUpsertRequest {

    @NotBlank(message = "计划编号不能为空")
    @Size(max = 40, message = "计划编号最长 40 个字符")
    private String planNo;

    @NotNull(message = "计划日期不能为空")
    private LocalDate planDate;

    @NotBlank(message = "编制人不能为空")
    @Size(max = 32, message = "编制人最长 32 个字符")
    private String ownerName;

    /** 片区日需求总量（m³），不允许为负 */
    @NotNull(message = "片区日需求量不能为空")
    private BigDecimal dailyDemandM3;

    /** 需求来源：调度预测 / 历史同期 / 大用户申报；提交复核时不可为空 */
    @Size(max = 256, message = "需求来源最长 256 个字符")
    private String demandSource;

    /** 变更说明（退回修改后重新提交、复制新版本时建议填写） */
    @Size(max = 512, message = "变更说明最长 512 个字符")
    private String changeNote;

    @Valid
    private List<QuotaItem> quotas;

    /** 单个站点配额行 */
    @Getter
    @Setter
    public static class QuotaItem {

        /** GATE 门站 / REGULATOR 调压站 */
        @NotBlank(message = "站点类型不能为空")
        private String stationType;

        @NotNull(message = "站点不能为空")
        private Long stationId;

        /** 配额（m³），为负 / 超能力在业务校验中给中文提示 */
        @NotNull(message = "配额不能为空")
        private BigDecimal quotaM3;

        @Size(max = 256, message = "配额备注最长 256 个字符")
        private String remark;
    }
}
