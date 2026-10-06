package com.citygas.network.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import com.citygas.network.common.Dict;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 供气配额行：计划版本下某个门站或调压站的当日配额拆分。
 *
 * <p>能力约束（提交时强制，草稿编辑实时校验提示）：
 * <ul>
 *   <li>门站：{@code quotaM3} 不得超过门站当日进站/输配能力 {@code capacityM3}
 *       （建档快照取门站日输气量能力），同站多行合并后仍不得超限；</li>
 *   <li>调压站：{@code quotaM3} 不得超过出口上限（按出口压力与口径核定）{@code capacityM3}；</li>
 *   <li>配额一律不得为负。</li>
 * </ul>
 * 编号/名称/能力均为版本快照：提交后档案变化不影响已保存配额，只在差异对比中提示。</p>
 */
@Entity
@Table(name = "gas_supply_quota")
@Getter
@Setter
public class GasSupplyQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** GATE 门站 / REGULATOR 调压站 */
    @Column(name = "station_type", nullable = false, length = 16)
    private String stationType;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    /** 站点编号快照（station_code） */
    @Column(name = "station_code", nullable = false, length = 24)
    private String stationCode;

    /** 站点名称快照 */
    @Column(name = "station_name", nullable = false, length = 64)
    private String stationName;

    /**
     * 能力上限快照（m³/日）：
     * 门站取进站输配能力（建档日输气量能力），调压站取出口供出上限。
     */
    @Column(name = "capacity_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal capacityM3;

    /** 当日供气配额（m³），不得为负、不得超过能力上限 */
    @Column(name = "quota_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal quotaM3;

    /** 配额备注（如大用户保供说明），可空 */
    @Column(name = "remark", length = 256)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isGate() {
        return Dict.GP_STATION_GATE.equals(stationType);
    }

    /** 是否超过能力上限 */
    public boolean isOverCapacity() {
        return quotaM3 != null && capacityM3 != null
                && quotaM3.compareTo(capacityM3) > 0;
    }

    /** 是否为负配额 */
    public boolean isNegative() {
        return quotaM3 != null && quotaM3.signum() < 0;
    }
}
