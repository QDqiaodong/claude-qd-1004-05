package com.citygas.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Commit;

import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanUpsertRequest;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasSupplyPlanRepository;
import com.citygas.network.service.GasSupplyPlanService;
import com.citygas.network.tenant.TenantContext;

/**
 * 供气计划版本并发冲突测试。
 *
 * <p>类级 {@link Commit}：两名操作员在各自独立线程/事务里同时复核或提交同一计划版本。
 * 行级悲观锁把两人串行化——先到者推进状态，后到者拿锁后读到已推进状态，
 * 收到 409 明确冲突：配额、复核意见、状态都保持先到者结果，绝无半推进。
 * 并发复制新版本则两人都成功，但版本号严格递增不撞号。</p>
 */
@Commit
class GasPlanConcurrentTest extends BaseIntegrationTest {

    private static final String TENANT = "A片区";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 21);

    @Autowired
    private GasSupplyPlanService planService;
    @Autowired
    private GasSupplyPlanRepository planRepository;

    private Long prepareDraft() {
        useTenant(TENANT);
        GateStation gate = newGate(TENANT, "GS-X01", "并发门站");
        gate.setDailyFlowM3(new BigDecimal("100000.00"));
        gateStationRepository.saveAndFlush(gate);
        RegulatorStation reg = newRegulator(TENANT, "RS-X01");
        reg.setOutletCapacityM3(new BigDecimal("60000.00"));
        regulatorRepository.saveAndFlush(reg);

        GasPlanUpsertRequest req = new GasPlanUpsertRequest();
        req.setPlanNo("GP-X01");
        req.setPlanDate(DATE);
        req.setOwnerName("计划员甲");
        req.setDailyDemandM3(new BigDecimal("130000.00"));
        req.setDemandSource("调度预测");
        req.setQuotas(new ArrayList<>(List.of(item(Dict.GP_STATION_GATE, gate.getId(), "80000.00"),
                item(Dict.GP_STATION_REGULATOR, reg.getId(), "50000.00"))));
        return planService.create(req).getId();
    }

    private Long prepareReviewing() {
        Long id = prepareDraft();
        planService.submitReview(id, action("计划员甲"));
        return id;
    }

    private Long prepareSubmitted() {
        Long id = prepareReviewing();
        planService.review(id, action("复核员甲", true, "同意"));
        return id;
    }

    private GasPlanUpsertRequest.QuotaItem item(String type, Long id, String quota) {
        GasPlanUpsertRequest.QuotaItem q = new GasPlanUpsertRequest.QuotaItem();
        q.setStationType(type);
        q.setStationId(id);
        q.setQuotaM3(new BigDecimal(quota));
        return q;
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

    @Test
    void concurrent_review_only_one_advances_loser_gets_conflict_without_overwriting() throws Exception {
        Long id = prepareReviewing();

        GasPlanActionRequest passA = action("复核员甲");
        passA.setPassed(true);
        passA.setReviewOpinion("甲的通过意见");
        GasPlanActionRequest passB = action("复核员乙");
        passB.setPassed(true);
        passB.setReviewOpinion("乙的通过意见");

        List<Outcome> outcomes = runParallel(
                () -> planService.review(id, passA),
                () -> planService.review(id, passB));

        long okCount = outcomes.stream().filter(Outcome::ok).count();
        long conflictCount = outcomes.stream().filter(o -> o.conflict).count();
        assertThat(okCount).as("恰有一人复核成功").isEqualTo(1);
        assertThat(conflictCount).as("另一人收到明确 409 冲突").isEqualTo(1);
        Outcome loser = outcomes.stream().filter(o -> o.conflict).findFirst().orElseThrow();
        assertThat(loser.error)
                .as("冲突消息明确且说明未改写配额与意见")
                .contains("冲突")
                .contains("已保存的配额与意见未被改写");

        TenantContext.set(TENANT);
        GasSupplyPlan after = planRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.GP_SUBMITTED);
        // 提交复核已让版本 +1；并发复核只一人成功，版本只再 +1（后来者不产生额外递增）
        assertThat(after.getVersion()).isEqualTo(2L);
        // 复核人与复核意见保持先到者，不被后来者覆盖
        assertThat(after.getReviewedBy()).isIn("复核员甲", "复核员乙");
        String winnerOpinion = "复核员甲".equals(after.getReviewedBy()) ? "甲的通过意见" : "乙的通过意见";
        var histories = jdbcTemplate.queryForList(
                "SELECT * FROM gas_supply_plan_history WHERE plan_id = ? AND action = '复核通过'", id);
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).get("review_opinion")).isEqualTo(winnerOpinion);
        assertThat(histories.get(0).get("operator_name")).isEqualTo(after.getReviewedBy());
    }

    @Test
    void concurrent_submit_review_only_one_advances_loser_gets_conflict() throws Exception {
        Long id = prepareDraft();

        List<Outcome> outcomes = runParallel(
                () -> planService.submitReview(id, action("计划员甲")),
                () -> planService.submitReview(id, action("计划员乙")));

        assertThat(outcomes.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.conflict).count())
                .as("两人同时提交同一草稿：后来者收到明确冲突").isEqualTo(1);

        TenantContext.set(TENANT);
        GasSupplyPlan after = planRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.GP_REVIEWING);
        // 提交人保持先到者，不被后来者覆盖
        assertThat(after.getSubmittedBy()).isIn("计划员甲", "计划员乙");
        Long submitLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gas_supply_plan_history WHERE plan_id = ? AND action = '提交复核'",
                Long.class, id);
        assertThat(submitLogs).isEqualTo(1L);
    }

    @Test
    void concurrent_freeze_only_one_advances_loser_gets_conflict() throws Exception {        Long id = prepareSubmitted();

        List<Outcome> outcomes = runParallel(
                () -> planService.freeze(id, action("调度主管甲")),
                () -> planService.freeze(id, action("调度主管乙")));

        assertThat(outcomes.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.conflict).count()).isEqualTo(1);

        TenantContext.set(TENANT);
        GasSupplyPlan after = planRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.GP_FROZEN);
        assertThat(after.getFrozenBy()).isIn("调度主管甲", "调度主管乙");
        Long freezeLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gas_supply_plan_history WHERE plan_id = ? AND action = '冻结'",
                Long.class, id);
        assertThat(freezeLogs).isEqualTo(1L);
    }

    @Test
    void concurrent_submit_review_with_one_invalid_request_keeps_draft_quotas_intact() throws Exception {
        // 构造超能力草稿：两人并发提交，都必须被拦截（先到者回滚放锁，后到者同样读到超能力）
        useTenant(TENANT);
        GateStation gate = newGate(TENANT, "GS-X02", "超限门站");
        gate.setDailyFlowM3(new BigDecimal("10000.00"));
        gateStationRepository.saveAndFlush(gate);

        GasPlanUpsertRequest req = new GasPlanUpsertRequest();
        req.setPlanNo("GP-X02");
        req.setPlanDate(DATE);
        req.setOwnerName("计划员甲");
        req.setDailyDemandM3(new BigDecimal("30000.00"));
        req.setDemandSource("调度预测");
        req.setQuotas(new ArrayList<>(List.of(item(Dict.GP_STATION_GATE, gate.getId(), "30000.00"))));
        Long id = planService.create(req).getId();

        List<Outcome> outcomes = runParallel(
                () -> planService.submitReview(id, action("计划员甲")),
                () -> planService.submitReview(id, action("计划员乙")));

        assertThat(outcomes).allMatch(o -> !o.ok && !o.conflict && o.error.contains("提交被拦截"));
        TenantContext.set(TENANT);
        GasSupplyPlan after = planRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.GP_DRAFT);
        assertThat(after.getSubmittedAt()).isNull();
        // 已保存配额原样保留，未被任何请求改写
        BigDecimal quota = jdbcTemplate.queryForObject(
                "SELECT quota_m3 FROM gas_supply_quota WHERE plan_id = ?", BigDecimal.class, id);
        assertThat(quota).isEqualByComparingTo("30000.00");
    }

    @Test
    void concurrent_copy_both_succeed_with_distinct_versions_and_source_untouched() throws Exception {
        Long id = prepareSubmitted();

        GasPlanActionRequest c1 = action("计划员甲");
        c1.setChangeNote("甲复制修订");
        GasPlanActionRequest c2 = action("计划员乙");
        c2.setChangeNote("乙复制修订");

        List<Outcome> outcomes = runParallel(
                () -> planService.copy(id, c1),
                () -> planService.copy(id, c2));

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(outcomes).extracting(Outcome::status)
                .containsExactlyInAnyOrder(Dict.GP_DRAFT, Dict.GP_DRAFT);

        TenantContext.set(TENANT);
        GasSupplyPlan source = planRepository.findById(id).orElseThrow();
        assertThat(source.getStatus()).as("源版本保持已提交，不被复制动作改写")
                .isEqualTo(Dict.GP_SUBMITTED);

        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT plan_version FROM gas_supply_plan WHERE plan_date = ? ORDER BY plan_version",
                Integer.class, java.sql.Date.valueOf(DATE));
        assertThat(versions).containsExactly(1, 2, 3);
    }

    // ---------------- 并行执行器 ----------------

    private List<Outcome> runParallel(java.util.function.Supplier<GasSupplyPlan> a,
                                      java.util.function.Supplier<GasSupplyPlan> b)
            throws Exception {
        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> runOne(a, ready, go)));
        futures.add(pool.submit(() -> runOne(b, ready, go)));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private Outcome runOne(java.util.function.Supplier<GasSupplyPlan> job,
                           CountDownLatch ready, CountDownLatch go) {
        TenantContext.set(TENANT);
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            GasSupplyPlan p = job.get();
            return new Outcome(true, false, p.getStatus(), null);
        } catch (ConflictException e) {
            return new Outcome(false, true, null, e.getMessage());
        } catch (Exception e) {
            return new Outcome(false, false, null,
                    (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private record Outcome(boolean ok, boolean conflict, String status, String error) {
    }
}
