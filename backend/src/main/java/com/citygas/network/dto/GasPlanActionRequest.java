package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 供气计划动作入参：提交复核 / 复核（通过/退回）/ 冻结 / 复制新版本。
 *
 * <p>两名操作员并发复核或提交时，后端行锁 + @Version 收口，后来者收到 409 明确冲突，
 * 不得改写已保存的配额、复核意见或状态。</p>
 */
@Getter
@Setter
public class GasPlanActionRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;

    /** 复核：true 通过（待复核→已提交），false 退回（待复核→草稿） */
    private Boolean passed;

    /** 复核意见：退回时必填；通过时选填 */
    @Size(max = 512, message = "复核意见最长 512 个字符")
    private String reviewOpinion;

    /** 变更说明：提交复核、复制新版本时填写 */
    @Size(max = 512, message = "变更说明最长 512 个字符")
    private String changeNote;

    /** 复制新版本时的新计划编号（租户内唯一），不填则自动派生 */
    private String newPlanNo;
}
