package com.citygas.network.controller;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanCapabilityVO;
import com.citygas.network.dto.GasPlanCompareVO;
import com.citygas.network.dto.GasPlanDetailVO;
import com.citygas.network.dto.GasPlanUpsertRequest;
import com.citygas.network.dto.PageResponse;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.service.GasSupplyPlanService;

import jakarta.validation.Valid;

/**
 * 门站日输气计划与供气配额编排接口。
 *
 * <pre>
 * 计划：GET  /api/gas-plans                       日期 + 状态分页筛选
 *       GET  /api/gas-plans/capability?planDate=  当日门站日输气量/调压站出口能力（只读本片区）
 *       POST /api/gas-plans                       创建草稿（配额拆分到门站/调压站）
 *       GET  /api/gas-plans/{id}                  详情（版本信息+配额+能力校验+复核记录）
 *       PUT  /api/gas-plans/{id}                  编辑草稿（仅草稿）
 *       POST /api/gas-plans/{id}/submit-review    提交复核（能力校验拦截，配额保留）
 *       POST /api/gas-plans/{id}/review           复核通过/退回（退回意见必填）
 *       POST /api/gas-plans/{id}/freeze           冻结（已提交→已冻结）
 *       POST /api/gas-plans/{id}/copy             复制生成新版本（仅已提交/已冻结）
 *       GET  /api/gas-plans/compare?fromId=&toId= 版本差异对比
 * </pre>
 * 租户由 X-Tenant 头 / ?tenant= 传入，计划只能读取、引用当前片区的站点。
 * 并发复核/提交同一版本时后来者收到 409，不得改写已保存的配额、复核意见或状态。
 */
@RestController
@RequestMapping("/api/gas-plans")
public class GasSupplyPlanController {

    private final GasSupplyPlanService planService;

    public GasSupplyPlanController(GasSupplyPlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public ApiResponse<PageResponse<GasSupplyPlan>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        Page<GasSupplyPlan> result = planService.search(planDate, status, page, size);
        return ApiResponse.success(PageResponse.of(result));
    }

    @GetMapping("/capability")
    public ApiResponse<GasPlanCapabilityVO> capability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        return ApiResponse.success(planService.capability(planDate));
    }

    @GetMapping("/compare")
    public ApiResponse<GasPlanCompareVO> compare(@RequestParam Long fromId, @RequestParam Long toId) {
        return ApiResponse.success(planService.compare(fromId, toId));
    }

    @GetMapping("/{id}")
    public ApiResponse<GasPlanDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(planService.detail(id));
    }

    @PostMapping
    public ApiResponse<GasSupplyPlan> create(@Valid @RequestBody GasPlanUpsertRequest req) {
        return ApiResponse.success("供气计划已创建（草稿）", planService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<GasSupplyPlan> update(@PathVariable Long id,
                                             @Valid @RequestBody GasPlanUpsertRequest req) {
        return ApiResponse.success("草稿配额已更新", planService.update(id, req));
    }

    @PostMapping("/{id}/submit-review")
    public ApiResponse<GasSupplyPlan> submitReview(@PathVariable Long id,
                                                   @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("已提交复核，等待复核结论", planService.submitReview(id, req));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<GasSupplyPlan> review(@PathVariable Long id,
                                             @Valid @RequestBody GasPlanActionRequest req) {
        boolean passed = Boolean.TRUE.equals(req.getPassed());
        String msg = passed ? "复核通过，计划已提交" : "已退回草稿，请按退回意见修改";
        return ApiResponse.success(msg, planService.review(id, req));
    }

    @PostMapping("/{id}/freeze")
    public ApiResponse<GasSupplyPlan> freeze(@PathVariable Long id,
                                             @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("计划已冻结，历史分配与复核意见已存档", planService.freeze(id, req));
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<GasSupplyPlan> copy(@PathVariable Long id,
                                           @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("已复制生成新版本（草稿），可继续编排配额", planService.copy(id, req));
    }
}
