package com.citygas.network;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 门站日输气计划与供气配额编排接口测试（MockMvc + H2）：
 * ① 日期/状态筛选与统一错误结构（校验失败 400、并发冲突 409）；
 * ② 能力上限（门站进站能力、调压站出口上限）、负配额、缺需求来源在接口层拦截并保留草稿；
 * ③ 提交→复核→冻结全链路 HTTP 反馈，复核退回意见必填；
 * ④ 提交后只能复制新版本；版本对比与已冻结历史分配/复核意见可查；
 * ⑤ 编制能力视图只返回当前片区站点。
 */
@AutoConfigureMockMvc
class GasPlanWebTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";
    private static final String DATE = "2026-10-25";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private MvcResult postJson(String url, String tenant, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header("X-Tenant", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult putJson(String url, String tenant, String body) throws Exception {
        return mockMvc.perform(put(url)
                        .header("X-Tenant", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private int stationSeq = 0;

    /** 建门站（能力 100000）+ 调压站（出口能力 60000），返回 [gateId, regulatorId] */
    private long[] prepareStations() throws Exception {
        int seq = ++stationSeq;
        String gateCode = String.format("GS-G%02d", seq);
        String regCode = String.format("RS-G%02d", seq);
        MvcResult gateR = postJson("/api/gate-stations", A, """
                {"stationCode":"%s","stationName":"城东供气门站","inletPressureMpa":1.2,
                 "outletPressureMpa":0.38,"dailyFlowM3":100000,"odorantMg":22,"status":"运行"}"""
                .formatted(gateCode));
        long gateId = body(gateR).path("data").path("id").asLong();
        MvcResult regR = postJson("/api/regulator-stations", A, """
                {"stationCode":"%s","stationName":"兴业供气调压站","stationType":"区域调压站",
                 "inPressureMpa":0.38,"outletPressureMpa":0.12,"outletCapacityM3":60000,
                 "status":"运行"}""".formatted(regCode));
        long regId = body(regR).path("data").path("id").asLong();
        return new long[] {gateId, regId};
    }

    private String upsertBody(String no, long gateId, String gateQuota,
                              long regId, String regQuota, String demandSource,
                              String changeNote) {
        StringBuilder quotas = new StringBuilder("[");
        boolean any = false;
        if (gateQuota != null) {
            quotas.append("{\"stationType\":\"GATE\",\"stationId\":").append(gateId)
                    .append(",\"quotaM3\":").append(gateQuota).append("}");
            any = true;
        }
        if (regQuota != null) {
            if (any) {
                quotas.append(",");
            }
            quotas.append("{\"stationType\":\"REGULATOR\",\"stationId\":").append(regId)
                    .append(",\"quotaM3\":").append(regQuota).append("}");
        }
        quotas.append("]");
        String sourcePart = demandSource == null ? ""
                : ",\"demandSource\":\"" + demandSource + "\"";
        String notePart = ",\"changeNote\":\"" + (changeNote == null ? "初排" : changeNote) + "\"";
        return """
                {"planNo":"%s","planDate":"%s","ownerName":"计划员甲","dailyDemandM3":130000%s%s,
                 "quotas":%s}
                """.formatted(no, DATE, sourcePart, notePart, quotas);
    }

    private String upsertBody(String no, long gateId, String gateQuota,
                              long regId, String regQuota, String demandSource) {
        return upsertBody(no, gateId, gateQuota, regId, regQuota, demandSource, "初排");
    }

    private String action(String operator) {
        return "{\"operator\":\"" + operator + "\"}";
    }

    private long createPlan(String no, String gateQuota, String regQuota, String source) throws Exception {
        long[] s = prepareStations();
        MvcResult r = postJson("/api/gas-plans", A,
                upsertBody(no, s[0], gateQuota, s[1], regQuota, source));
        return body(r).path("data").path("id").asLong();
    }

    // ---------------- 能力视图与筛选 ----------------

    @Test
    void capability_view_lists_only_current_tenant_stations() throws Exception {
        prepareStations();
        mockMvc.perform(get("/api/gas-plans/capability?planDate=" + DATE).header("X-Tenant", A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planDate").value(DATE))
                .andExpect(jsonPath("$.data.gateStations[0].dailyFlowM3").value(100000))
                .andExpect(jsonPath("$.data.regulatorStations[0].outletCapacityM3").value(60000));

        // B 片区看不到 A 的站点
        mockMvc.perform(get("/api/gas-plans/capability?planDate=" + DATE).header("X-Tenant", B))
                .andExpect(jsonPath("$.data.gateStations").isArray())
                .andExpect(jsonPath("$.data.gateStations.length()").value(0));
    }

    @Test
    void list_filters_by_date_and_status() throws Exception {
        long id = createPlan("GP-L01", "80000", "50000", "调度预测");
        mockMvc.perform(post("/api/gas-plans/" + id + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/gas-plans?planDate=" + DATE + "&status=待复核").header("X-Tenant", A))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("待复核"));
        mockMvc.perform(get("/api/gas-plans?planDate=2020-01-01").header("X-Tenant", A))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/gas-plans?status=草稿").header("X-Tenant", A))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------------- 能力上限与提交拦截 ----------------

    @Test
    void over_gate_capacity_submit_blocked_with_unified_error_and_draft_kept() throws Exception {
        long id = createPlan("GP-V01", "100000.01", "50000", "调度预测");

        // 详情已标记能力超限
        mockMvc.perform(get("/api/gas-plans/" + id).header("X-Tenant", A))
                .andExpect(jsonPath("$.data.blockCount", org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(post("/api/gas-plans/" + id + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("提交被拦截"),
                                org.hamcrest.Matchers.containsString("超过进站能力"))));

        // 草稿状态与已保存配额原样保留
        mockMvc.perform(get("/api/gas-plans/" + id).header("X-Tenant", A))
                .andExpect(jsonPath("$.data.plan.status").value("草稿"))
                .andExpect(jsonPath("$.data.gateQuotas[0].quotaM3").value(
                        org.hamcrest.Matchers.closeTo(100000.01, 0.001)));
    }

    @Test
    void over_regulator_capacity_negative_quota_and_missing_source_blocked() throws Exception {
        // 调压站超出口
        long idOver = createPlan("GP-V02", "50000", "60000.01", "调度预测");
        mockMvc.perform(post("/api/gas-plans/" + idOver + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("超过出口上限")));

        // 负配额
        long[] s = prepareStations();
        String neg = upsertBody("GP-V03", s[0], "-1", s[1], "10000", "调度预测");
        MvcResult negR = postJson("/api/gas-plans", A, neg);
        long idNeg = body(negR).path("data").path("id").asLong();
        mockMvc.perform(post("/api/gas-plans/" + idNeg + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("配额为负")));

        // 缺需求来源
        long idNoSrc = createPlan("GP-V04", "50000", "10000", null);
        mockMvc.perform(post("/api/gas-plans/" + idNoSrc + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("缺少需求来源")));
    }

    @Test
    void cross_tenant_station_reference_rejected_and_plan_invisible() throws Exception {
        MvcResult gateB = postJson("/api/gate-stations", B, """
                {"stationCode":"GS-GB1","stationName":"B片区门站","inletPressureMpa":1.2,
                 "outletPressureMpa":0.38,"dailyFlowM3":100000,"odorantMg":22,"status":"运行"}""");
        long gateIdB = body(gateB).path("data").path("id").asLong();
        String bad = """
                {"planNo":"GP-V05","planDate":"%s","ownerName":"计划员甲","dailyDemandM3":10000,
                 "demandSource":"调度预测",
                 "quotas":[{"stationType":"GATE","stationId":%d,"quotaM3":10000}]}
                """.formatted(DATE, gateIdB);
        mockMvc.perform(post("/api/gas-plans").header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在或不属于当前片区")));

        long idA = createPlan("GP-V06", "50000", "10000", "调度预测");
        mockMvc.perform(get("/api/gas-plans/" + idA).header("X-Tenant", B))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("供气计划不存在")));
    }

    // ---------------- 全链路：复核退回/通过/冻结 + 复制 + 对比 ----------------

    @Test
    void full_chain_reject_requires_opinion_then_pass_freeze_copy_and_compare() throws Exception {
        long id = createPlan("GP-W01", "90000", "55000", "调度预测");

        // 提交复核
        mockMvc.perform(post("/api/gas-plans/" + id + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("待复核"))
                .andExpect(jsonPath("$.data.submittedBy").value("计划员甲"));

        // 非待复核状态重复提交 → 冲突/非法跳转
        mockMvc.perform(post("/api/gas-plans/" + id + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员乙")))
                .andExpect(status().isConflict());

        // 退回意见必填
        mockMvc.perform(post("/api/gas-plans/" + id + "/review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"复核员甲","passed":false,"reviewOpinion":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("退回意见")));

        // 退回草稿
        mockMvc.perform(post("/api/gas-plans/" + id + "/review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"复核员甲","passed":false,"reviewOpinion":"门站配额压到 8 万以内"}"""))
                .andExpect(jsonPath("$.data.status").value("草稿"));

        // 修改配额后重新提交
        long[] s = prepareStations();
        mockMvc.perform(put("/api/gas-plans/" + id).header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody("GP-W01", s[0], "80000", s[1], "50000",
                                "调度预测", "按复核意见压减")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/gas-plans/" + id + "/submit-review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(jsonPath("$.data.status").value("待复核"));

        // 复核通过
        mockMvc.perform(post("/api/gas-plans/" + id + "/review")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"复核员乙","passed":true,"reviewOpinion":"同意"}"""))
                .andExpect(jsonPath("$.data.status").value("已提交"))
                .andExpect(jsonPath("$.data.reviewedBy").value("复核员乙"));

        // 已提交不可编辑
        mockMvc.perform(put("/api/gas-plans/" + id).header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody("GP-W01", s[0], "1", s[1], "1", "调度预测")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("草稿")));

        // 冻结
        mockMvc.perform(post("/api/gas-plans/" + id + "/freeze")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("调度主管")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已冻结"));

        // 已冻结：历史分配与复核意见可查
        mockMvc.perform(get("/api/gas-plans/" + id).header("X-Tenant", A))
                .andExpect(jsonPath("$.data.plan.status").value("已冻结"))
                .andExpect(jsonPath("$.data.gateQuotas[0].quotaM3").value(
                        org.hamcrest.Matchers.closeTo(80000, 0.001)))
                .andExpect(jsonPath("$.data.histories[?(@.action=='复核退回')].reviewOpinion")
                        .value(org.hamcrest.Matchers.hasItem("门站配额压到 8 万以内")))
                .andExpect(jsonPath("$.data.histories[?(@.action=='复核通过')].reviewOpinion")
                        .value(org.hamcrest.Matchers.hasItem("同意")));

        // 冻结后复制新版本必须填变更说明
        mockMvc.perform(post("/api/gas-plans/" + id + "/copy")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content(action("计划员甲")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("变更说明")));

        // 复制成功
        MvcResult copyR = mockMvc.perform(post("/api/gas-plans/" + id + "/copy")
                        .header("X-Tenant", A).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"计划员甲","changeNote":"次日调增 5000"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("草稿"))
                .andExpect(jsonPath("$.data.planVersion").value(2))
                .andExpect(jsonPath("$.data.copiedFromId").value(id))
                .andReturn();
        long v2Id = body(copyR).path("data").path("id").asLong();

        // 源版本仍已冻结
        mockMvc.perform(get("/api/gas-plans/" + id).header("X-Tenant", A))
                .andExpect(jsonPath("$.data.plan.status").value("已冻结"));

        // 版本对比
        mockMvc.perform(get("/api/gas-plans/compare")
                        .header("X-Tenant", A)
                        .param("fromId", String.valueOf(id))
                        .param("toId", String.valueOf(v2Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromVersion").value(1))
                .andExpect(jsonPath("$.data.toVersion").value(2))
                .andExpect(jsonPath("$.data.quotaDiffs.length()").value(2))
                .andExpect(jsonPath("$.data.quotaDiffs[*].changeType",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.equalTo("SAME"))));
    }

    @Test
    void create_with_blank_owner_returns_field_validation_error() throws Exception {
        long[] s = prepareStations();
        String body = """
                {"planNo":"GP-BAD","planDate":"%s","ownerName":"   ","dailyDemandM3":10000,
                 "quotas":[{"stationType":"GATE","stationId":%d,"quotaM3":1000}]}
                """.formatted(DATE, s[0]);
        mockMvc.perform(post("/api/gas-plans").header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("编制人不能为空")));
    }
}
