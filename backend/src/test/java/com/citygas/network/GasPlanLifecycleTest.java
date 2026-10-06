package com.citygas.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanCompareVO;
import com.citygas.network.dto.GasPlanDetailVO;
import com.citygas.network.dto.GasPlanQuotaVO;
import com.citygas.network.dto.GasPlanUpsertRequest;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.service.GasSupplyPlanService;

/**
 * 门站日输气计划与供气配额编排 · 业务生命周期测试：
 * ① 能力约束下的配额编排：门站进站能力、调压站出口上限、负配额、缺需求来源；
 * ② 草稿→待复核→已提交→已冻结全链路与复核退回意见必填；
 * ③ 提交拦截整体回滚、配额/意见原样保留；
 * ④ 提交后只读，只能复制生成新版本；复制刷新能力快照；
 * ⑤ 版本差异对比、已冻结历史分配与复核意见可查；
 * ⑥ 计划只能引用本片区站点（跨租户视为不存在）。
 */
class GasPlanLifecycleTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    @Autowired
    private GasSupplyPlanService planService;

    // ---------------- 夹具 ----------------

    /** 门站能力 100000；调压站出口能力 60000 */
    private StationFixture stations() {
        useTenant(A);
        GateStation gate = newGate(A, "GS-P01", "城东计划门站");
        gate.setDailyFlowM3(new BigDecimal("100000.00"));
        gate = gateStationRepository.saveAndFlush(gate);
        RegulatorStation reg = newRegulator(A, "RS-P01");
        reg.setStationName("兴业计划调压站");
        reg.setOutletCapacityM3(new BigDecimal("60000.00"));
        reg = regulatorRepository.saveAndFlush(reg);
        return new StationFixture(gate, reg);
    }

    private GasPlanUpsertRequest draftReq(String no, BigDecimal gateQuota, BigDecimal regQuota,
                                          String demandSource) {
        GasPlanUpsertRequest req = new GasPlanUpsertRequest();
        req.setPlanNo(no);
        req.setPlanDate(DATE);
        req.setOwnerName("计划员甲");
        req.setDailyDemandM3(new BigDecimal("140000.00"));
        req.setDemandSource(demandSource);
        req.setChangeNote("初次编排");
        req.setQuotas(new java.util.ArrayList<>());
        if (gateQuota != null) {
            req.getQuotas().add(item(Dict.GP_STATION_GATE, stationsHolder.gate.getId(), gateQuota));
        }
        if (regQuota != null) {
            req.getQuotas().add(item(Dict.GP_STATION_REGULATOR, stationsHolder.reg.getId(), regQuota));
        }
        return req;
    }

    private StationFixture stationsHolder;

    private GasPlanUpsertRequest.QuotaItem item(String type, Long id, String quota) {
        return item(type, id, new BigDecimal(quota));
    }

    private GasPlanUpsertRequest.QuotaItem item(String type, Long id, BigDecimal quota) {
        GasPlanUpsertRequest.QuotaItem q = new GasPlanUpsertRequest.QuotaItem();
        q.setStationType(type);
        q.setStationId(id);
        q.setQuotaM3(quota);
        return q;
    }

    private record StationFixture(GateStation gate, RegulatorStation reg) {
    }

    private GasPlanActionRequest action(String operator) {
        return action(operator, null, null);
    }

    private GasPlanActionRequest action(String operator, Boolean passed, String opinion) {
        GasPlanActionRequest a = new GasPlanActionRequest();
        a.setOperator(operator);
        a.setPassed(passed);
        a.setReviewOpinion(opinion);
        return a;
    }

    // ---------------- 草稿与能力校验 ----------------

    @Test
    void draft_saves_over_capacity_but_detail_marks_block() {
        stationsHolder = stations();
        Long id = planService.create(
                draftReq("GP-D01", new BigDecimal("120000.00"), new BigDecimal("50000.00"), null))
                .getId();

        GasPlanDetailVO d = planService.detail(id);
        assertThat(d.getPlan().getStatus()).isEqualTo(Dict.GP_DRAFT);
        // 门站配额 120000 > 进站能力 100000：配额已保存，详情标记拦截
        GasPlanQuotaVO gate = d.getGateQuotas().get(0);
        assertThat(gate.isOverCapacity()).isTrue();
        assertThat(d.getBlockCount()).isGreaterThan(0);
        assertThat(d.getGateQuotaTotal()).isEqualByComparingTo("120000.00");
        assertThat(d.getGateCapacityTotal()).isEqualByComparingTo("100000.00");
    }

    @Test
    void submit_blocked_by_gate_capacity_negative_quota_and_missing_demand_source() {
        stationsHolder = stations();

        // 超门站进站能力
        Long overId = planService.create(
                draftReq("GP-B01", new BigDecimal("100000.01"), new BigDecimal("10000.00"), "调度预测"))
                .getId();
        assertThatThrownBy(() -> planService.submitReview(overId, action("计划员甲")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("提交被拦截")
                .hasMessageContaining("超过进站能力");
        assertThat(planService.detail(overId).getPlan().getStatus()).isEqualTo(Dict.GP_DRAFT);

        // 调压站超出口上限
        Long regOverId = planService.create(
                draftReq("GP-B02", new BigDecimal("50000.00"), new BigDecimal("60000.01"), "调度预测"))
                .getId();
        assertThatThrownBy(() -> planService.submitReview(regOverId, action("计划员甲")))
                .isInstanceOf(BizException.class).hasMessageContaining("超过出口上限");

        // 负配额
        GasPlanUpsertRequest negReq = draftReq("GP-B03",
                new BigDecimal("-1.00"), new BigDecimal("10000.00"), "调度预测");
        Long negId = planService.create(negReq).getId();
        assertThatThrownBy(() -> planService.submitReview(negId, action("计划员甲")))
                .isInstanceOf(BizException.class).hasMessageContaining("配额为负");

        // 缺需求来源
        Long noSourceId = planService.create(
                draftReq("GP-B04", new BigDecimal("50000.00"), new BigDecimal("10000.00"), null))
                .getId();
        assertThatThrownBy(() -> planService.submitReview(noSourceId, action("计划员甲")))
                .isInstanceOf(BizException.class).hasMessageContaining("缺少需求来源");

        // 拦截后配额原样保留
        GasPlanDetailVO kept = planService.detail(overId);
        assertThat(kept.getGateQuotas().get(0).getQuotaM3()).isEqualByComparingTo("100000.01");
        assertThat(kept.getPlan().getSubmittedAt()).isNull();
    }

    @Test
    void regulator_without_capacity_cannot_carry_positive_quota() {
        useTenant(A);
        GateStation gate = newGate(A, "GS-P02", "能力门站");
        gate.setDailyFlowM3(new BigDecimal("100000.00"));
        gateStationRepository.saveAndFlush(gate);
        RegulatorStation reg = newRegulator(A, "RS-P02");
        reg.setOutletCapacityM3(null);
        regulatorRepository.saveAndFlush(reg);

        GasPlanUpsertRequest req = new GasPlanUpsertRequest();
        req.setPlanNo("GP-B05");
        req.setPlanDate(DATE);
        req.setOwnerName("计划员甲");
        req.setDailyDemandM3(new BigDecimal("31000.00"));
        req.setDemandSource("大用户申报");
        req.setQuotas(new java.util.ArrayList<>(List.of(
                item(Dict.GP_STATION_GATE, gate.getId(), "30000.00"),
                item(Dict.GP_STATION_REGULATOR, reg.getId(), "1000.00"))));
        Long id = planService.create(req).getId();

        assertThatThrownBy(() -> planService.submitReview(id, action("计划员甲")))
                .isInstanceOf(BizException.class).hasMessageContaining("出口供出能力未核定");
    }

    @Test
    void draft_negative_demand_and_duplicate_station_rows_rejected() {
        stationsHolder = stations();
        GasPlanUpsertRequest req = draftReq("GP-B06",
                new BigDecimal("10000.00"), null, "调度预测");
        req.setDailyDemandM3(new BigDecimal("-5.00"));
        assertThatThrownBy(() -> planService.create(req))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为负");

        GasPlanUpsertRequest dup = draftReq("GP-B07", null, null, "调度预测");
        dup.getQuotas().add(item(Dict.GP_STATION_GATE, stationsHolder.gate.getId(), "1000.00"));
        dup.getQuotas().add(item(Dict.GP_STATION_GATE, stationsHolder.gate.getId(), "2000.00"));
        assertThatThrownBy(() -> planService.create(dup))
                .isInstanceOf(BizException.class).hasMessageContaining("同一站点");
    }

    // ---------------- 状态机全链路 ----------------

    @Test
    void full_chain_draft_review_submit_freeze_with_histories() {
        stationsHolder = stations();
        Long id = planService.create(
                draftReq("GP-F01", new BigDecimal("80000.00"), new BigDecimal("50000.00"), "调度预测"))
                .getId();

        // 提交复核
        GasSupplyPlan reviewing = planService.submitReview(id, action("计划员甲"));
        assertThat(reviewing.getStatus()).isEqualTo(Dict.GP_REVIEWING);
        assertThat(reviewing.getSubmittedBy()).isEqualTo("计划员甲");

        // 退回意见必填
        assertThatThrownBy(() -> planService.review(id, action("复核员甲", false, "  ")))
                .isInstanceOf(BizException.class).hasMessageContaining("退回意见");

        // 退回草稿
        GasSupplyPlan back = planService.review(id, action("复核员甲", false, "门站配额偏高，请压减"));
        assertThat(back.getStatus()).isEqualTo(Dict.GP_DRAFT);

        // 修改后重新提交、复核通过
        GasPlanUpsertRequest revised = draftReq("GP-F01",
                new BigDecimal("70000.00"), new BigDecimal("45000.00"), "调度预测");
        revised.setChangeNote("按复核意见压减门站配额");
        planService.update(id, revised);
        planService.submitReview(id, action("计划员甲"));
        GasSupplyPlan submitted = planService.review(id, action("复核员乙", true, "同意"));
        assertThat(submitted.getStatus()).isEqualTo(Dict.GP_SUBMITTED);
        assertThat(submitted.getReviewedBy()).isEqualTo("复核员乙");

        // 已提交不可编辑、不可再次提交复核（顺序操作的非法跳转给 400 业务错误）
        assertThatThrownBy(() -> planService.update(id, revised))
                .isInstanceOf(BizException.class).hasMessageContaining("草稿");
        assertThatThrownBy(() -> planService.submitReview(id, action("计划员甲")))
                .isInstanceOf(BizException.class).hasMessageContaining("非法状态跳转");

        // 冻结
        GasSupplyPlan frozen = planService.freeze(id, action("调度主管"));
        assertThat(frozen.getStatus()).isEqualTo(Dict.GP_FROZEN);
        assertThat(frozen.getFrozenBy()).isEqualTo("调度主管");

        // 历史记录：提交复核、退回、再提交、通过、冻结，复核意见可查
        GasPlanDetailVO d = planService.detail(id);
        List<String> actions = d.getHistories().stream().map(GasPlanDetailVO.HistoryVO::getAction).toList();
        assertThat(actions).containsExactly(
                Dict.GP_ACTION_SUBMIT_REVIEW, Dict.GP_ACTION_REVIEW_REJECT,
                Dict.GP_ACTION_SUBMIT_REVIEW, Dict.GP_ACTION_REVIEW_PASS, Dict.GP_ACTION_FREEZE);
        assertThat(d.getHistories())
                .anyMatch(h -> Dict.GP_ACTION_REVIEW_REJECT.equals(h.getAction())
                        && "门站配额偏高，请压减".equals(h.getReviewOpinion()))
                .anyMatch(h -> Dict.GP_ACTION_REVIEW_PASS.equals(h.getAction())
                        && "同意".equals(h.getReviewOpinion()));
    }

    // ---------------- 提交后复制新版本 ----------------

    @Test
    void copy_after_submit_creates_new_draft_version_and_refreshes_capacity() {
        stationsHolder = stations();
        Long id = planService.create(
                draftReq("GP-C01", new BigDecimal("80000.00"), new BigDecimal("50000.00"), "历史同期"))
                .getId();
        planService.submitReview(id, action("计划员甲"));
        planService.review(id, action("复核员甲", true, null));

        // 已提交版本复制新版本，变更说明必填
        GasPlanActionRequest noNote = action("计划员乙");
        assertThatThrownBy(() -> planService.copy(id, noNote))
                .isInstanceOf(BizException.class).hasMessageContaining("变更说明");

        GasPlanActionRequest copyReq = action("计划员乙");
        copyReq.setChangeNote("次日大用户增产，调增调压站配额");
        GasSupplyPlan v2 = planService.copy(id, copyReq);
        assertThat(v2.getStatus()).isEqualTo(Dict.GP_DRAFT);
        assertThat(v2.getPlanVersion()).isEqualTo(2);
        assertThat(v2.getCopiedFromId()).isEqualTo(id);
        assertThat(v2.getPlanNo()).isEqualTo("GP-C01-V2");
        assertThat(v2.getChangeNote()).contains("调增调压站配额");

        // 配额已复制到新版本
        GasPlanDetailVO d2 = planService.detail(v2.getId());
        assertThat(d2.getGateQuotas()).hasSize(1);
        assertThat(d2.getGateQuotas().get(0).getQuotaM3()).isEqualByComparingTo("80000.00");
        assertThat(d2.getRegulatorQuotas().get(0).getQuotaM3()).isEqualByComparingTo("50000.00");
        assertThat(d2.getHistories()).extracting(GasPlanDetailVO.HistoryVO::getAction)
                .containsExactly(Dict.GP_ACTION_COPY);

        // 原版本一行不动，仍为已提交
        assertThat(planService.detail(id).getPlan().getStatus()).isEqualTo(Dict.GP_SUBMITTED);

        // 档案能力变化后，复制新版本按当前能力刷新快照
        useTenant(A);
        GateStation gate = stationsHolder.gate;
        gate.setDailyFlowM3(new BigDecimal("130000.00"));
        gateStationRepository.saveAndFlush(gate);
        planService.submitReview(v2.getId(), action("计划员乙"));
        planService.review(v2.getId(), action("复核员甲", true, null));
        GasPlanActionRequest copy3 = action("计划员乙");
        copy3.setChangeNote("门站扩容后重排");
        GasSupplyPlan v3 = planService.copy(v2.getId(), copy3);
        assertThat(v3.getPlanVersion()).isEqualTo(3);
        GasPlanDetailVO d3 = planService.detail(v3.getId());
        assertThat(d3.getGateQuotas().get(0).getCapacityM3()).isEqualByComparingTo("130000.00");
        // 原 v1 快照能力不变
        assertThat(planService.detail(id).getGateQuotas().get(0).getCapacityM3())
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void draft_cannot_copy_and_frozen_copy_keeps_history_queryable() {
        stationsHolder = stations();
        Long id = planService.create(
                draftReq("GP-C02", new BigDecimal("80000.00"), new BigDecimal("50000.00"), "历史同期"))
                .getId();
        GasPlanActionRequest req = action("计划员甲");
        req.setChangeNote("变更");
        assertThatThrownBy(() -> planService.copy(id, req))
                .isInstanceOf(BizException.class).hasMessageContaining("已提交");

        planService.submitReview(id, action("计划员甲"));
        planService.review(id, action("复核员甲", true, "通过"));
        planService.freeze(id, action("调度主管"));

        // 冻结后仍可复制；冻结版本历史分配与复核意见持续可查
        GasPlanActionRequest copyReq = action("计划员甲");
        copyReq.setChangeNote("冻结后例行修订");
        GasSupplyPlan next = planService.copy(id, copyReq);
        assertThat(next.getStatus()).isEqualTo(Dict.GP_DRAFT);

        GasPlanDetailVO frozenDetail = planService.detail(id);
        assertThat(frozenDetail.getPlan().getStatus()).isEqualTo(Dict.GP_FROZEN);
        assertThat(frozenDetail.getGateQuotas().get(0).getQuotaM3()).isEqualByComparingTo("80000.00");
        assertThat(frozenDetail.getHistories())
                .anyMatch(h -> Dict.GP_ACTION_REVIEW_PASS.equals(h.getAction())
                        && "通过".equals(h.getReviewOpinion()));
    }

    // ---------------- 版本差异对比 ----------------

    @Test
    void compare_versions_reports_added_removed_changed_and_totals() {
        stationsHolder = stations();
        Long v1Id = planService.create(
                draftReq("GP-DIFF", new BigDecimal("80000.00"), new BigDecimal("50000.00"), "历史同期"))
                .getId();
        planService.submitReview(v1Id, action("计划员甲"));
        planService.review(v1Id, action("复核员甲", true, null));

        GasPlanActionRequest copyReq = action("计划员甲");
        copyReq.setChangeNote("重排");
        Long v2Id = planService.copy(v1Id, copyReq).getId();
        GasPlanUpsertRequest v2Req = draftReq("GP-DIFF-V2",
                new BigDecimal("90000.00"), new BigDecimal("55000.00"), "调度预测");
        // 保持与 v1 相同的站点行，调整配额；planNo 字段在 update 不使用
        planService.update(v2Id, v2Req);

        GasPlanCompareVO cmp = planService.compare(v1Id, v2Id);
        assertThat(cmp.getFromVersion()).isEqualTo(1);
        assertThat(cmp.getToVersion()).isEqualTo(2);
        assertThat(cmp.getGateTotalDelta()).isEqualByComparingTo("10000.00");
        assertThat(cmp.getRegulatorTotalDelta()).isEqualByComparingTo("5000.00");
        assertThat(cmp.getQuotaDiffs()).hasSize(2);
        assertThat(cmp.getQuotaDiffs())
                .allSatisfy(d -> assertThat(d.getChangeType()).isEqualTo("CHANGED"));

        // v2 删除调压站行后再对比：出现 REMOVED
        GasPlanUpsertRequest v2OnlyGate = draftReq("GP-DIFF-V2",
                new BigDecimal("90000.00"), null, "调度预测");
        planService.update(v2Id, v2OnlyGate);
        GasPlanCompareVO cmp2 = planService.compare(v1Id, v2Id);
        assertThat(cmp2.getQuotaDiffs())
                .anySatisfy(d -> {
                    assertThat(d.getChangeType()).isEqualTo("REMOVED");
                    assertThat(d.getStationType()).isEqualTo(Dict.GP_STATION_REGULATOR);
                });
    }

    // ---------------- 租户隔离 ----------------

    @Test
    void plan_can_only_reference_current_tenant_stations() {
        useTenant(B);
        GateStation gateB = newGate(B, "GS-PB1", "B片区门站");

        useTenant(A);
        stationsHolder = stations();
        GasPlanUpsertRequest req = draftReq("GP-T01",
                new BigDecimal("10000.00"), new BigDecimal("10000.00"), "调度预测");
        // 用 B 片区门站 ID 替换
        req.getQuotas().clear();
        req.getQuotas().add(item(Dict.GP_STATION_GATE, gateB.getId(), "10000.00"));
        req.getQuotas().add(item(Dict.GP_STATION_REGULATOR, stationsHolder.reg.getId(), "10000.00"));
        assertThatThrownBy(() -> planService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("门站不存在或不属于当前片区");

        // A 的计划对 B 不可见
        Long idA = planService.create(
                draftReq("GP-T02", new BigDecimal("10000.00"), new BigDecimal("10000.00"), "调度预测"))
                .getId();
        useTenant(B);
        assertThatThrownBy(() -> planService.detail(idA))
                .isInstanceOf(BizException.class).hasMessageContaining("供气计划不存在");
    }

    // ---------------- 筛选 ----------------

    @Test
    void search_filters_by_date_and_status() {
        stationsHolder = stations();
        Long d1 = planService.create(
                draftReq("GP-S01", new BigDecimal("10000.00"), new BigDecimal("10000.00"), "调度预测"))
                .getId();
        planService.submitReview(d1, action("计划员甲"));

        var page = planService.search(DATE, Dict.GP_REVIEWING, 0, 10);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(d1);

        var byDate = planService.search(DATE, null, 0, 10);
        assertThat(byDate.getTotalElements()).isEqualTo(1);
        assertThat(planService.search(LocalDate.of(2020, 1, 1), null, 0, 10)
                .getTotalElements()).isZero();
    }
}
