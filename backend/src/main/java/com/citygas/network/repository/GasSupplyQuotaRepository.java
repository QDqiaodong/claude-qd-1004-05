package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GasSupplyQuota;

/** 供气配额 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface GasSupplyQuotaRepository extends JpaRepository<GasSupplyQuota, Long> {

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select q from GasSupplyQuota q where q.id = :id")
    Optional<GasSupplyQuota> findScopedById(@Param("id") Long id);

    List<GasSupplyQuota> findByPlanIdOrderByIdAsc(Long planId);
}
