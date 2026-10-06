package com.citygas.network.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 供气计划流转记录：提交复核 / 复核通过 / 复核退回 / 冻结 / 复制新版本全程留痕，
 * 复核意见随退回/通过动作保存。已冻结版本的历史分配与复核意见必须可查，
 * 因此记录一经写入不可修改、不可删除。
 */
@Entity
@Table(name = "gas_supply_plan_history")
@Getter
@Setter
public class GasSupplyPlanHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 提交复核/复核通过/复核退回/冻结/复制新版本 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    /** 复核意见（复核通过/退回时填写，退回时必填） */
    @Column(name = "review_opinion", length = 512)
    private String reviewOpinion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
