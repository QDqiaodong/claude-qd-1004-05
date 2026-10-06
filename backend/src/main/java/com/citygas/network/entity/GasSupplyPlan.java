package com.citygas.network.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 门站日输气计划：按日查看门站日输气量、调压站出口能力与片区需求，
 * 在能力约束下把供气配额拆分到门站与调压站。
 *
 * <p>同一片区同一日期可迭代多版：版本号 {@code planVersion} 从 1 递增，
 * 计划编号 {@code planNo} 租户内唯一；{@code copiedFromId} 记录本版本由哪个已提交/冻结版本复制而来。</p>
 *
 * <p>状态机：草稿 → 待复核 → 已提交 → 已冻结（待复核可退回草稿）。
 * 草稿可反复编排配额；提交后版本冻结，只能复制生成新版本；已冻结版本的历史分配与复核意见永久可查。</p>
 *
 * <p>{@code version} 为 JPA 乐观锁，配合状态推进时的行级悲观锁：两名操作员同时复核或提交时
 * 只有一人推进版本，后来者收到 409，且不得改写已保存的配额、复核意见或状态。</p>
 */
@Entity
@Table(name = "gas_supply_plan")
@Getter
@Setter
public class GasSupplyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    /** 计划编号，租户内唯一 */
    @Column(name = "plan_no", nullable = false, length = 40)
    private String planNo;

    /** 计划供气日期 */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    /** 版本号，同片区同日期从 1 递增 */
    @Column(name = "plan_version", nullable = false)
    private Integer planVersion;

    /** 由哪个已提交/冻结版本复制而来；首版为空 */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    /** 编制人 */
    @Column(name = "owner_name", nullable = false, length = 32)
    private String ownerName;

    /** 片区日需求总量（m³），需求来源说明非空时必填且不能为负 */
    @Column(name = "daily_demand_m3", nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal dailyDemandM3;

    /** 需求来源：调度预测/历史同期/大用户申报，提交时不可为空 */
    @Column(name = "demand_source", length = 256)
    private String demandSource;

    /** 最近一次变更说明（复制新版本 / 退回后修改时填写） */
    @Column(name = "change_note", length = 512)
    private String changeNote;

    /** 草稿 / 待复核 / 已提交 / 已冻结 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 32)
    private String submittedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    @Column(name = "frozen_by", length = 32)
    private String frozenBy;

    /** 乐观锁：并发复核/提交只允许一人推进版本 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
