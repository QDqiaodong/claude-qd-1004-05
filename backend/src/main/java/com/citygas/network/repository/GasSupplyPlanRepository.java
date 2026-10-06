package com.citygas.network.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GasSupplyPlan;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/** 供气计划 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface GasSupplyPlanRepository extends JpaRepository<GasSupplyPlan, Long> {

    Optional<GasSupplyPlan> findByPlanNo(String planNo);

    boolean existsByPlanNo(String planNo);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select p from GasSupplyPlan p where p.id = :id")
    Optional<GasSupplyPlan> findScopedById(@Param("id") Long id);

    /** 日期 + 状态分页筛选，租户条件由 Hibernate 自动追加 */
    @Query("""
            select p from GasSupplyPlan p
            where (:planDate is null or p.planDate = :planDate)
              and (:status is null or p.status = :status)
            order by p.planDate desc, p.planVersion desc, p.id desc
            """)
    Page<GasSupplyPlan> search(@Param("planDate") LocalDate planDate,
                               @Param("status") String status,
                               Pageable pageable);

    /** 同片区同日期已占用的最大版本号，复制新版本时在锁内取号 */
    @Query("select coalesce(max(p.planVersion), 0) from GasSupplyPlan p where p.planDate = :planDate")
    Integer findMaxVersionByDate(@Param("planDate") LocalDate planDate);

    /**
     * 复制新版本取号锁：锁定同日期最新版本行，把同一日期的并发复制串行化，
     * 保证版本号连续不撞号（数据库另有 (租户,日期,版本号) 唯一约束托底）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("""
            select p from GasSupplyPlan p
            where p.planDate = :planDate
            order by p.planVersion desc, p.id desc
            """)
    java.util.List<GasSupplyPlan> findLatestForDateForUpdate(@Param("planDate") LocalDate planDate,
                                                             Pageable pageable);

    /**
     * 状态推进收口用的行级悲观锁：两名操作员并发复核/提交同一计划时，
     * 后到者拿锁后读到已推进的状态，收到明确冲突，不能改写先到者的配额、复核意见或状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from GasSupplyPlan p where p.id = :id")
    Optional<GasSupplyPlan> findByIdForUpdate(@Param("id") Long id);
}
