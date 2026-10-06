package com.citygas.network.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.citygas.network.entity.GasSupplyPlanHistory;

/** 供气计划流转记录 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface GasSupplyPlanHistoryRepository extends JpaRepository<GasSupplyPlanHistory, Long> {

    List<GasSupplyPlanHistory> findByPlanIdOrderByIdAsc(Long planId);
}
