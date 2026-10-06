package com.citygas.network.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanCapabilityVO;
import com.citygas.network.dto.GasPlanCompareVO;
import com.citygas.network.dto.GasPlanDetailVO;
import com.citygas.network.dto.GasPlanQuotaVO;
import com.citygas.network.dto.GasPlanUpsertRequest;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.GasSupplyPlanHistory;
import com.citygas.network.entity.GasSupplyQuota;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasSupplyPlanHistoryRepository;
import com.citygas.network.repository.GasSupplyPlanRepository;
import com.citygas.network.repository.GasSupplyQuotaRepository;
import com.citygas.network.repository.GateStationRepository;
import com.citygas.network.repository.RegulatorStationRepository;

/**
 * 门站日输气计划与供气配额编排核心业务。
 *
 * <p>版本状态机：草稿 → 待复核 → 已提交 → 已冻结（待复核可退回草稿）：
 * <ul>
 *   <li>草稿：按日期查看本片区门站日输气量（进站能力）、调压站出口能力与片区需求，
 *       可反复编排配额，配额校验实时提示但不拦截保存；</li>
 *   <li>提交复核：行锁内逐项校验——门站配额不得超过进站能力、调压站配额不得超过出口上限、
 *       配额不得为负、需求来源必填、需求量不得为负；任一拦截项成立整体回滚，配额原样保留；</li>
 *   <li>复核：通过推进到已提交；退回回到草稿且退回意见必填，复核意见全程留痕；</li>
 *   <li>已提交 / 已冻结：版本只读，只能复制生成新版本继续编排；
 *       已冻结版本的历史分配与复核意见永久可查。</li>
 * </ul>
 * 两名操作员并发复核或提交同一版本时，行级悲观锁 + @Version 双保险收口：
 * 只一人推进版本，后来者收到 409 明确冲突，且不得改写已保存的配额、复核意见或状态。
 * 所有读写均经 {@code @TenantId} 自动隔离，配额只能引用当前片区的站点。</p>
 */
@Service
public class GasSupplyPlanService {

    private final GasSupplyPlanRepository planRepository;
    private final GasSupplyQuotaRepository quotaRepository;
    private final GasSupplyPlanHistoryRepository historyRepository;
    private final GateStationRepository gateRepository;
    private final RegulatorStationRepository regulatorRepository;

    public GasSupplyPlanService(GasSupplyPlanRepository planRepository,
                                GasSupplyQuotaRepository quotaRepository,
                                GasSupplyPlanHistoryRepository historyRepository,
                                GateStationRepository gateRepository,
                                RegulatorStationRepository regulatorRepository) {
        this.planRepository = planRepository;
        this.quotaRepository = quotaRepository;
        this.historyRepository = historyRepository;
        this.gateRepository = gateRepository;
        this.regulatorRepository = regulatorRepository;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Page<GasSupplyPlan> search(LocalDate planDate, String status, int page, int size) {
        if (status != null) {
            status = status.trim();
            if (status.isEmpty()) {
                status = null;
            }
        }
        if (status != null && !Dict.isValidGasPlanStatus(status)) {
            throw new BizException("计划状态不合法：" + status);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return planRepository.search(planDate, status, pageable);
    }

    /** 编制视图：当前片区门站日输气量（进站能力）与调压站出口能力，按日期只读返回。 */
    @Transactional(readOnly = true)
    public GasPlanCapabilityVO capability(LocalDate planDate) {
        if (planDate == null) {
            throw new BizException("计划日期不能为空");
        }
        GasPlanCapabilityVO vo = new GasPlanCapabilityVO();
        vo.setPlanDate(planDate.toString());
        List<GasPlanCapabilityVO.Gate> gates = new ArrayList<>();
        for (GateStation g : gateRepository.findAll()) {
            GasPlanCapabilityVO.Gate row = new GasPlanCapabilityVO.Gate();
            row.setId(g.getId());
            row.setStationCode(g.getStationCode());
            row.setStationName(g.getStationName());
            row.setDailyFlowM3(g.getDailyFlowM3());
            row.setInletPressureMpa(g.getInletPressureMpa());
            row.setStatus(g.getStatus());
            gates.add(row);
        }
        List<GasPlanCapabilityVO.Regulator> regs = new ArrayList<>();
        for (RegulatorStation r : regulatorRepository.findAll()) {
            GasPlanCapabilityVO.Regulator row = new GasPlanCapabilityVO.Regulator();
            row.setId(r.getId());
            row.setStationCode(r.getStationCode());
            row.setStationName(r.getStationName());
            row.setStationType(r.getStationType());
            row.setOutletPressureMpa(r.getOutletPressureMpa());
            row.setOutletCapacityM3(r.getOutletCapacityM3());
            row.setStatus(r.getStatus());
            regs.add(row);
        }
        vo.setGateStations(gates);
        vo.setRegulatorStations(regs);
        return vo;
    }

    @Transactional(readOnly = true)
    public GasPlanDetailVO detail(Long id) {
        GasSupplyPlan plan = getPlan(id);
        return buildDetail(plan, quotaRepository.findByPlanIdOrderByIdAsc(id),
                historyRepository.findByPlanIdOrderByIdAsc(id));
    }

    /** 两个版本的配额差异对比（均可为已提交/已冻结的历史版本）。 */
    @Transactional(readOnly = true)
    public GasPlanCompareVO compare(Long fromId, Long toId) {
        GasSupplyPlan from = getPlan(fromId);
        GasSupplyPlan to = getPlan(toId);
        List<GasSupplyQuota> fromRows = quotaRepository.findByPlanIdOrderByIdAsc(fromId);
        List<GasSupplyQuota> toRows = quotaRepository.findByPlanIdOrderByIdAsc(toId);

        Map<String, GasSupplyQuota> fromMap = keyByStation(fromRows);
        Map<String, GasSupplyQuota> toMap = keyByStation(toRows);
        Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(fromMap.keySet());
        keys.addAll(toMap.keySet());

        List<GasPlanCompareVO.QuotaDiff> diffs = new ArrayList<>();
        BigDecimal fromGate = BigDecimal.ZERO, toGate = BigDecimal.ZERO;
        BigDecimal fromReg = BigDecimal.ZERO, toReg = BigDecimal.ZERO;
        for (String key : keys) {
            GasSupplyQuota a = fromMap.get(key);
            GasSupplyQuota b = toMap.get(key);
            GasPlanCompareVO.QuotaDiff d = new GasPlanCompareVO.QuotaDiff();
            GasSupplyQuota base = a != null ? a : b;
            d.setStationType(base.getStationType());
            d.setStationId(base.getStationId());
            d.setStationCode(base.getStationCode());
            d.setStationName(base.getStationName());
            d.setFromCapacity(a == null ? null : a.getCapacityM3());
            d.setToCapacity(b == null ? null : b.getCapacityM3());
            d.setFromQuota(a == null ? null : a.getQuotaM3());
            d.setToQuota(b == null ? null : b.getQuotaM3());
            if (a == null) {
                d.setChangeType("ADDED");
                d.setQuotaDelta(b.getQuotaM3());
            } else if (b == null) {
                d.setChangeType("REMOVED");
                d.setQuotaDelta(a.getQuotaM3().negate());
            } else if (a.getQuotaM3().compareTo(b.getQuotaM3()) != 0) {
                d.setChangeType("CHANGED");
                d.setQuotaDelta(b.getQuotaM3().subtract(a.getQuotaM3()));
            } else {
                d.setChangeType("SAME");
                d.setQuotaDelta(BigDecimal.ZERO);
            }
            diffs.add(d);

            if (Dict.GP_STATION_GATE.equals(base.getStationType())) {
                if (a != null) {
                    fromGate = fromGate.add(a.getQuotaM3());
                }
                if (b != null) {
                    toGate = toGate.add(b.getQuotaM3());
                }
            } else {
                if (a != null) {
                    fromReg = fromReg.add(a.getQuotaM3());
                }
                if (b != null) {
                    toReg = toReg.add(b.getQuotaM3());
                }
            }
        }

        GasPlanCompareVO vo = new GasPlanCompareVO();
        vo.setFromPlanId(from.getId());
        vo.setToPlanId(to.getId());
        vo.setFromPlanNo(from.getPlanNo());
        vo.setToPlanNo(to.getPlanNo());
        vo.setFromVersion(from.getPlanVersion());
        vo.setToVersion(to.getPlanVersion());
        vo.setFromStatus(from.getStatus());
        vo.setToStatus(to.getStatus());
        vo.setFromDemand(from.getDailyDemandM3());
        vo.setToDemand(to.getDailyDemandM3());
        vo.setDemandDelta(to.getDailyDemandM3().subtract(from.getDailyDemandM3()));
        vo.setFromGateTotal(fromGate);
        vo.setToGateTotal(toGate);
        vo.setGateTotalDelta(toGate.subtract(fromGate));
        vo.setFromRegulatorTotal(fromReg);
        vo.setToRegulatorTotal(toReg);
        vo.setRegulatorTotalDelta(toReg.subtract(fromReg));
        vo.setQuotaDiffs(diffs);
        return vo;
    }

    // ==================== 草稿：创建 / 编辑 ====================

    @Transactional
    public GasSupplyPlan create(GasPlanUpsertRequest req) {
        validateUpsert(req);
        String planNo = req.getPlanNo().trim();
        if (planRepository.existsByPlanNo(planNo)) {
            throw new BizException("计划编号已存在：" + planNo);
        }

        // 锁定同日期版本序列，把并发创建/复制串行化，保证版本号不撞号
        // （数据库另有 (租户,日期,版本号) 唯一约束托底）
        planRepository.findLatestForDateForUpdate(req.getPlanDate(), PageRequest.of(0, 1));

        LocalDateTime now = LocalDateTime.now();
        GasSupplyPlan plan = new GasSupplyPlan();
        plan.setPlanNo(planNo);
        plan.setPlanDate(req.getPlanDate());
        plan.setPlanVersion(nextVersionNumber(req.getPlanDate()));
        plan.setOwnerName(req.getOwnerName().trim());
        applyPlanFields(plan, req, now);
        plan.setStatus(Dict.GP_DRAFT);
        plan = planRepository.save(plan);
        rebuildQuotas(plan, req.getQuotas(), now);
        return plan;
    }

    /** 编辑草稿：仅「草稿」可改；校验失败/状态不对时已保存配额原样保留。 */
    @Transactional
    public GasSupplyPlan update(Long id, GasPlanUpsertRequest req) {
        GasSupplyPlan plan = getPlan(id);
        requireDraft(plan);
        validateUpsert(req);

        LocalDateTime now = LocalDateTime.now();
        applyPlanFields(plan, req, now);
        plan = planRepository.save(plan);
        rebuildQuotas(plan, req.getQuotas(), now);
        return plan;
    }

    private void applyPlanFields(GasSupplyPlan plan, GasPlanUpsertRequest req, LocalDateTime now) {
        plan.setDailyDemandM3(req.getDailyDemandM3());
        plan.setDemandSource(trimToNull(req.getDemandSource()));
        plan.setChangeNote(trimToNull(req.getChangeNote()));
        plan.setUpdatedAt(now);
        if (plan.getCreatedAt() == null) {
            plan.setCreatedAt(now);
        }
    }

    private void validateUpsert(GasPlanUpsertRequest req) {
        if (req.getDailyDemandM3() == null || req.getDailyDemandM3().signum() < 0) {
            throw new BizException("片区日需求量不能为空且不能为负");
        }
        if (req.getQuotas() == null) {
            return;
        }
        // 结构性校验：同一计划里同站只能一行；引用站点必须存在且属于当前片区。
        // 负配额 / 超能力属于编排校验，草稿允许保存以便前端持续提示，提交时拦截。
        Set<String> dedup = new HashSet<>();
        for (GasPlanUpsertRequest.QuotaItem item : req.getQuotas()) {
            String type = item.getStationType();
            if (!Dict.GP_STATION_GATE.equals(type) && !Dict.GP_STATION_REGULATOR.equals(type)) {
                throw new BizException("站点类型不合法：" + type);
            }
            if (item.getQuotaM3() == null) {
                throw new BizException("配额不能为空");
            }
            String key = type + "#" + item.getStationId();
            if (!dedup.add(key)) {
                throw new BizException("同一站点在本计划中只能拆分一条配额，请合并后重试");
            }
            if (Dict.GP_STATION_GATE.equals(type)) {
                gateRepository.findScopedById(item.getStationId())
                        .orElseThrow(() -> new BizException(
                                "门站不存在或不属于当前片区：" + item.getStationId()));
            } else {
                regulatorRepository.findScopedById(item.getStationId())
                        .orElseThrow(() -> new BizException(
                                "调压站不存在或不属于当前片区：" + item.getStationId()));
            }
        }
    }

    /** 重建草稿配额：删除旧行 → 按当前档案快照站点编号/名称/能力。 */
    private void rebuildQuotas(GasSupplyPlan plan, List<GasPlanUpsertRequest.QuotaItem> items,
                               LocalDateTime now) {
        List<GasSupplyQuota> old = quotaRepository.findByPlanIdOrderByIdAsc(plan.getId());
        quotaRepository.deleteAll(old);
        quotaRepository.flush();
        if (items == null) {
            return;
        }
        for (GasPlanUpsertRequest.QuotaItem item : items) {
            GasSupplyQuota row = new GasSupplyQuota();
            row.setPlanId(plan.getId());
            row.setStationType(item.getStationType());
            row.setStationId(item.getStationId());
            row.setQuotaM3(item.getQuotaM3());
            row.setRemark(trimToNull(item.getRemark()));
            if (Dict.GP_STATION_GATE.equals(item.getStationType())) {
                GateStation g = gateRepository.findScopedById(item.getStationId())
                        .orElseThrow(() -> new BizException(
                                "门站不存在或不属于当前片区：" + item.getStationId()));
                row.setStationCode(g.getStationCode());
                row.setStationName(g.getStationName());
                // 门站进站/输配能力取日输气量
                row.setCapacityM3(g.getDailyFlowM3() == null ? BigDecimal.ZERO : g.getDailyFlowM3());
            } else {
                RegulatorStation r = regulatorRepository.findScopedById(item.getStationId())
                        .orElseThrow(() -> new BizException(
                                "调压站不存在或不属于当前片区：" + item.getStationId()));
                row.setStationCode(r.getStationCode());
                row.setStationName(r.getStationName());
                // 出口能力未核定时按 0 快照，提交时以「能力未核定」拦截
                row.setCapacityM3(r.getOutletCapacityM3() == null
                        ? BigDecimal.ZERO : r.getOutletCapacityM3());
            }
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            quotaRepository.save(row);
        }
    }

    // ==================== 提交复核 / 复核 / 冻结（行锁收口） ====================

    /**
     * 提交复核：行锁内复查状态并逐项做能力校验，任一拦截项成立整体回滚，
     * 绝不推进状态、已编辑配额与说明原样保留。
     */
    @Transactional
    public GasSupplyPlan submitReview(Long id, GasPlanActionRequest req) {
        requireOperator(req.getOperator());
        // 并发场景只可能是「另一名操作员刚把草稿提交到待复核」；其他状态属非法跳转
        GasSupplyPlan plan = lockPlan(id, Dict.GP_DRAFT,
                java.util.Set.of(Dict.GP_REVIEWING), "提交复核");
        List<GasSupplyQuota> rows = quotaRepository.findByPlanIdOrderByIdAsc(id);

        List<String> blocks = collectSubmitBlocks(plan, rows);
        if (!blocks.isEmpty()) {
            throw new BizException("提交被拦截，已保留全部配额与说明，请调整后重试："
                    + String.join("；", blocks));
        }

        LocalDateTime now = LocalDateTime.now();
        String before = plan.getStatus();
        plan.setStatus(Dict.GP_REVIEWING);
        plan.setSubmittedAt(now);
        plan.setSubmittedBy(req.getOperator().trim());
        if (req.getChangeNote() != null && !req.getChangeNote().isBlank()) {
            plan.setChangeNote(req.getChangeNote().trim());
        }
        plan.setUpdatedAt(now);
        plan = planRepository.save(plan);
        recordHistory(plan, Dict.GP_ACTION_SUBMIT_REVIEW, before, Dict.GP_REVIEWING,
                req.getOperator(), trimToNull(req.getReviewOpinion()), now);
        return plan;
    }

    /**
     * 复核：{@code passed=true} 待复核→已提交；{@code passed=false} 退回草稿（退回意见必填）。
     * 两名复核员并发复核时只一人推进，后来者 409，不得改写先到者的复核意见。
     */
    @Transactional
    public GasSupplyPlan review(Long id, GasPlanActionRequest req) {
        requireOperator(req.getOperator());
        if (req.getPassed() == null) {
            throw new BizException("复核结论不能为空（通过/退回）");
        }
        // 并发复核：另一人已推进到已提交（通过）或草稿（退回），后来者收 409
        GasSupplyPlan plan = lockPlan(id, Dict.GP_REVIEWING,
                java.util.Set.of(Dict.GP_SUBMITTED, Dict.GP_DRAFT), "复核");
        LocalDateTime now = LocalDateTime.now();
        String before = plan.getStatus();
        if (Boolean.TRUE.equals(req.getPassed())) {
            plan.setStatus(Dict.GP_SUBMITTED);
            plan.setReviewedAt(now);
            plan.setReviewedBy(req.getOperator().trim());
            plan.setUpdatedAt(now);
            plan = planRepository.save(plan);
            recordHistory(plan, Dict.GP_ACTION_REVIEW_PASS, before, Dict.GP_SUBMITTED,
                    req.getOperator(), trimToNull(req.getReviewOpinion()), now);
        } else {
            if (req.getReviewOpinion() == null || req.getReviewOpinion().isBlank()) {
                throw new BizException("复核退回时必须填写退回意见");
            }
            plan.setStatus(Dict.GP_DRAFT);
            plan.setUpdatedAt(now);
            plan = planRepository.save(plan);
            recordHistory(plan, Dict.GP_ACTION_REVIEW_REJECT, before, Dict.GP_DRAFT,
                    req.getOperator(), req.getReviewOpinion().trim(), now);
        }
        return plan;
    }

    /** 冻结：已提交→已冻结。冻结后历史分配与复核意见永久可查。 */
    @Transactional
    public GasSupplyPlan freeze(Long id, GasPlanActionRequest req) {
        requireOperator(req.getOperator());
        GasSupplyPlan plan = lockPlan(id, Dict.GP_SUBMITTED,
                java.util.Set.of(Dict.GP_FROZEN), "冻结");
        LocalDateTime now = LocalDateTime.now();
        String before = plan.getStatus();
        plan.setStatus(Dict.GP_FROZEN);
        plan.setFrozenAt(now);
        plan.setFrozenBy(req.getOperator().trim());
        plan.setUpdatedAt(now);
        plan = planRepository.save(plan);
        recordHistory(plan, Dict.GP_ACTION_FREEZE, before, Dict.GP_FROZEN,
                req.getOperator(), trimToNull(req.getReviewOpinion()), now);
        return plan;
    }

    // ==================== 提交后复制新版本 ====================

    /**
     * 已提交/已冻结版本只能复制生成新版本：同日期取下一版本号，状态为草稿，
     * 配额按当前档案刷新编号/名称/能力快照（已不存在的站点保留旧快照并在详情中拦截提示）。
     * 原版本一行不动；复制动作在新版本上留痕，必须填写变更说明。
     */
    @Transactional
    public GasSupplyPlan copy(Long id, GasPlanActionRequest req) {
        requireOperator(req.getOperator());
        if (req.getChangeNote() == null || req.getChangeNote().isBlank()) {
            throw new BizException("复制新版本时必须填写变更说明");
        }

        GasSupplyPlan source = planRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("供气计划不存在：" + id));
        if (!Dict.GP_SUBMITTED.equals(source.getStatus())
                && !Dict.GP_FROZEN.equals(source.getStatus())) {
            throw new BizException("只有「已提交」或「已冻结」的版本可以复制新版本，当前为「"
                    + source.getStatus() + "」");
        }

        // 锁定同日期版本序列，把并发复制串行化，保证版本号不撞号
        planRepository.findLatestForDateForUpdate(source.getPlanDate(), PageRequest.of(0, 1));
        int nextVersion = nextVersionNumber(source.getPlanDate());

        String newPlanNo;
        if (req.getNewPlanNo() != null && !req.getNewPlanNo().isBlank()) {
            newPlanNo = req.getNewPlanNo().trim();
            if (planRepository.existsByPlanNo(newPlanNo)) {
                throw new BizException("计划编号已存在：" + newPlanNo);
            }
        } else {
            newPlanNo = source.getPlanNo() + "-V" + nextVersion;
            int suffix = 2;
            while (planRepository.existsByPlanNo(newPlanNo)) {
                newPlanNo = source.getPlanNo() + "-V" + nextVersion + "-" + suffix++;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        GasSupplyPlan copy = new GasSupplyPlan();
        copy.setPlanNo(newPlanNo);
        copy.setPlanDate(source.getPlanDate());
        copy.setPlanVersion(nextVersion);
        copy.setCopiedFromId(source.getId());
        copy.setOwnerName(req.getOperator().trim());
        copy.setDailyDemandM3(source.getDailyDemandM3());
        copy.setDemandSource(source.getDemandSource());
        copy.setChangeNote(req.getChangeNote().trim());
        copy.setStatus(Dict.GP_DRAFT);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy = planRepository.save(copy);

        for (GasSupplyQuota src : quotaRepository.findByPlanIdOrderByIdAsc(source.getId())) {
            GasSupplyQuota row = new GasSupplyQuota();
            row.setPlanId(copy.getId());
            row.setStationType(src.getStationType());
            row.setStationId(src.getStationId());
            row.setQuotaM3(src.getQuotaM3());
            row.setRemark(src.getRemark());
            // 按当前档案刷新快照；站点已不在本片区则保留旧快照，由详情校验提示
            if (Dict.GP_STATION_GATE.equals(src.getStationType())) {
                Optional<GateStation> g = gateRepository.findScopedById(src.getStationId());
                if (g.isPresent()) {
                    row.setStationCode(g.get().getStationCode());
                    row.setStationName(g.get().getStationName());
                    row.setCapacityM3(g.get().getDailyFlowM3() == null
                            ? BigDecimal.ZERO : g.get().getDailyFlowM3());
                } else {
                    row.setStationCode(src.getStationCode());
                    row.setStationName(src.getStationName());
                    row.setCapacityM3(src.getCapacityM3());
                }
            } else {
                Optional<RegulatorStation> r = regulatorRepository.findScopedById(src.getStationId());
                if (r.isPresent()) {
                    row.setStationCode(r.get().getStationCode());
                    row.setStationName(r.get().getStationName());
                    row.setCapacityM3(r.get().getOutletCapacityM3() == null
                            ? BigDecimal.ZERO : r.get().getOutletCapacityM3());
                } else {
                    row.setStationCode(src.getStationCode());
                    row.setStationName(src.getStationName());
                    row.setCapacityM3(src.getCapacityM3());
                }
            }
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            quotaRepository.save(row);
        }

        recordHistory(copy, Dict.GP_ACTION_COPY, source.getStatus(), Dict.GP_DRAFT,
                req.getOperator(), req.getChangeNote().trim(), now);
        return copy;
    }

    // ==================== 详情装配与能力校验 ====================

    private GasPlanDetailVO buildDetail(GasSupplyPlan plan, List<GasSupplyQuota> rows,
                                        List<GasSupplyPlanHistory> histories) {
        GasPlanDetailVO vo = new GasPlanDetailVO();
        vo.setPlan(plan);

        List<GasPlanQuotaVO> gates = new ArrayList<>();
        List<GasPlanQuotaVO> regs = new ArrayList<>();
        BigDecimal gateQuota = BigDecimal.ZERO;
        BigDecimal gateCapacity = BigDecimal.ZERO;
        BigDecimal regQuota = BigDecimal.ZERO;
        BigDecimal regCapacity = BigDecimal.ZERO;

        for (GasSupplyQuota row : rows) {
            GasPlanQuotaVO q = GasPlanQuotaVO.of(row);
            boolean frozen = isReadonly(plan.getStatus());
            fillCurrentAndDiffs(q, frozen);
            if (Dict.GP_STATION_GATE.equals(row.getStationType())) {
                gates.add(q);
                gateQuota = gateQuota.add(nz(row.getQuotaM3()));
                gateCapacity = gateCapacity.add(nz(row.getCapacityM3()));
            } else {
                regs.add(q);
                regQuota = regQuota.add(nz(row.getQuotaM3()));
                regCapacity = regCapacity.add(nz(row.getCapacityM3()));
            }
            for (GasPlanQuotaVO.DiffItem d : q.getDiffs()) {
                if ("BLOCK".equals(d.getLevel())) {
                    vo.setBlockCount(vo.getBlockCount() + 1);
                } else {
                    vo.setWarnCount(vo.getWarnCount() + 1);
                }
            }
        }

        vo.setGateQuotas(gates);
        vo.setRegulatorQuotas(regs);
        vo.setGateQuotaTotal(gateQuota);
        vo.setGateCapacityTotal(gateCapacity);
        vo.setRegulatorQuotaTotal(regQuota);
        vo.setRegulatorCapacityTotal(regCapacity);

        // 计划级拦截项：需求来源缺失、需求量为负、门站总量超进站能力合计
        if (plan.getDemandSource() == null || plan.getDemandSource().isBlank()) {
            addPlanBlock(vo, "缺少需求来源，请填写调度预测/历史同期/大用户申报等来源后再提交");
        }
        if (plan.getDailyDemandM3() != null && plan.getDailyDemandM3().signum() < 0) {
            addPlanBlock(vo, "片区日需求量为负，请先修正");
        }
        if (gateQuota.compareTo(gateCapacity) > 0) {
            addPlanBlock(vo, "门站配额合计 " + gateQuota + " m³ 超过进站能力合计 "
                    + gateCapacity + " m³");
        }

        // 仅提示项：配额合计未覆盖片区需求、配额合计为 0
        if (plan.getDailyDemandM3() != null && plan.getDailyDemandM3().signum() > 0) {
            BigDecimal allQuota = gateQuota.add(regQuota);
            if (allQuota.compareTo(plan.getDailyDemandM3()) < 0) {
                vo.setWarnCount(vo.getWarnCount() + 1);
            }
        }

        List<GasPlanDetailVO.HistoryVO> historyVOS = histories.stream()
                .map(GasPlanDetailVO.HistoryVO::of).toList();
        vo.setHistories(historyVOS);
        return vo;
    }

    private void addPlanBlock(GasPlanDetailVO vo, String message) {
        vo.setBlockCount(vo.getBlockCount() + 1);
    }

    private void fillCurrentAndDiffs(GasPlanQuotaVO q, boolean frozen) {
        if (Dict.GP_STATION_GATE.equals(q.getStationType())) {
            Optional<GateStation> opt = gateRepository.findScopedById(q.getStationId());
            if (opt.isEmpty()) {
                q.setPresent(false);
                q.getDiffs().add(new GasPlanQuotaVO.DiffItem("BLOCK", "DELETED",
                        frozen ? "门站已从当前片区档案中删除（不改变历史分配）"
                               : "门站已从当前片区档案中删除，请移除该配额行"));
                return;
            }
            GateStation g = opt.get();
            q.setPresent(true);
            q.setCurrentStationName(g.getStationName());
            q.setCurrentStatus(g.getStatus());
            q.setCurrentCapacityM3(g.getDailyFlowM3());
            addCommonDiffs(q, g.getStationName(), g.getStatus(), g.getDailyFlowM3(), frozen,
                    "门站");
        } else {
            Optional<RegulatorStation> opt = regulatorRepository.findScopedById(q.getStationId());
            if (opt.isEmpty()) {
                q.setPresent(false);
                q.getDiffs().add(new GasPlanQuotaVO.DiffItem("BLOCK", "DELETED",
                        frozen ? "调压站已从当前片区档案中删除（不改变历史分配）"
                               : "调压站已从当前片区档案中删除，请移除该配额行"));
                return;
            }
            RegulatorStation r = opt.get();
            q.setPresent(true);
            q.setCurrentStationName(r.getStationName());
            q.setCurrentStatus(r.getStatus());
            q.setCurrentCapacityM3(r.getOutletCapacityM3());
            BigDecimal currentCapacity = r.getOutletCapacityM3() == null
                    ? BigDecimal.ZERO : r.getOutletCapacityM3();
            addCommonDiffs(q, r.getStationName(), r.getStatus(), currentCapacity, frozen, "调压站");
        }
    }

    private void addCommonDiffs(GasPlanQuotaVO q, String currentName, String currentStatus,
                                BigDecimal currentCapacity, boolean frozen, String label) {
        // 负配额 / 超能力：提交拦截项（草稿阶段即红色提示）
        if (q.isNegative()) {
            q.getDiffs().add(new GasPlanQuotaVO.DiffItem("BLOCK", "NEGATIVE",
                    label + "配额不能为负"));
        }
        boolean capacityUnset = Dict.GP_STATION_REGULATOR.equals(q.getStationType())
                && currentCapacity.signum() == 0 && nz(q.getQuotaM3()).signum() > 0;
        if (capacityUnset) {
            q.getDiffs().add(new GasPlanQuotaVO.DiffItem("BLOCK", "CAPACITY_UNSET",
                    "调压站出口供出能力未核定，不能编排大于 0 的配额"));
        } else if (nz(q.getQuotaM3()).compareTo(nz(q.getCapacityM3())) > 0) {
            String kind = Dict.GP_STATION_GATE.equals(q.getStationType())
                    ? "OVER_GATE_CAPACITY" : "OVER_REGULATOR_CAPACITY";
            String limit = Dict.GP_STATION_GATE.equals(q.getStationType()) ? "进站能力" : "出口上限";
            q.getDiffs().add(new GasPlanQuotaVO.DiffItem("BLOCK", kind,
                    label + "配额 " + q.getQuotaM3() + " m³ 超过" + limit + " "
                            + q.getCapacityM3() + " m³"));
        }

        // 非运行状态：仅提示，不改变已保存配额
        if (currentStatus != null && !"运行".equals(currentStatus)) {
            q.getDiffs().add(new GasPlanQuotaVO.DiffItem("WARN", "DISABLED",
                    label + "当前状态为「" + currentStatus + "」，配额执行存在风险"
                            + (frozen ? "（不改变历史分配）" : "")));
        }
        if (currentName != null && !currentName.equals(q.getStationName())) {
            q.getDiffs().add(new GasPlanQuotaVO.DiffItem("WARN", "NAME_CHANGED",
                    label + "名称已由「" + q.getStationName() + "」变更为「" + currentName
                            + "」（配额保留原快照）"));
        }
        if (currentCapacity != null && currentCapacity.compareTo(nz(q.getCapacityM3())) != 0) {
            q.getDiffs().add(new GasPlanQuotaVO.DiffItem("WARN", "CAPACITY_CHANGED",
                    label + "能力已由 " + q.getCapacityM3() + " m³ 核定为 " + currentCapacity
                            + " m³" + (frozen ? "（历史分配保持不变）" : "，重新保存草稿后按新能力校验")));
        }
    }

    /** 提交复核前的硬校验：缺需求来源、负配额、超进站/出口能力、能力未核定、站点缺失。 */
    private List<String> collectSubmitBlocks(GasSupplyPlan plan, List<GasSupplyQuota> rows) {
        List<String> blocks = new ArrayList<>();
        if (plan.getDemandSource() == null || plan.getDemandSource().isBlank()) {
            blocks.add("缺少需求来源，无法提交");
        }
        if (plan.getDailyDemandM3() == null || plan.getDailyDemandM3().signum() < 0) {
            blocks.add("片区日需求量不能为空且不能为负");
        }
        for (GasSupplyQuota row : rows) {
            String label = (row.isGate() ? "门站 " : "调压站 ") + row.getStationCode();
            if (row.isNegative()) {
                blocks.add(label + " 配额为负");
                continue;
            }
            if (row.isGate()) {
                GateStation g = gateRepository.findScopedById(row.getStationId()).orElse(null);
                if (g == null) {
                    blocks.add(label + " 已不属于当前片区，无法提交");
                    continue;
                }
                BigDecimal capacity = g.getDailyFlowM3() == null ? BigDecimal.ZERO : g.getDailyFlowM3();
                if (row.getQuotaM3().compareTo(capacity) > 0) {
                    blocks.add(label + " 配额 " + row.getQuotaM3() + " m³ 超过进站能力 "
                            + capacity + " m³");
                }
            } else {
                RegulatorStation r = regulatorRepository.findScopedById(row.getStationId()).orElse(null);
                if (r == null) {
                    blocks.add(label + " 已不属于当前片区，无法提交");
                    continue;
                }
                if (r.getOutletCapacityM3() == null) {
                    blocks.add(label + " 出口供出能力未核定");
                    continue;
                }
                if (row.getQuotaM3().compareTo(r.getOutletCapacityM3()) > 0) {
                    blocks.add(label + " 配额 " + row.getQuotaM3() + " m³ 超过出口上限 "
                            + r.getOutletCapacityM3() + " m³");
                }
            }
        }
        // 门站总量：所有门站配额合计不得超过进站能力合计（逐站满足时自然成立，此处再兜底）
        BigDecimal quotaSum = rows.stream().filter(GasSupplyQuota::isGate)
                .map(GasSupplyQuota::getQuotaM3).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capacitySum = BigDecimal.ZERO;
        for (GasSupplyQuota row : rows) {
            if (!row.isGate()) {
                continue;
            }
            GateStation g = gateRepository.findScopedById(row.getStationId()).orElse(null);
            if (g != null && g.getDailyFlowM3() != null) {
                capacitySum = capacitySum.add(g.getDailyFlowM3());
            }
        }
        if (quotaSum.compareTo(capacitySum) > 0) {
            blocks.add("门站配额合计 " + quotaSum + " m³ 超过进站能力合计 " + capacitySum + " m³");
        }
        return blocks;
    }

    // ==================== 公共辅助 ====================

    private GasSupplyPlan getPlan(Long id) {
        return planRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("供气计划不存在：" + id));
    }

    private void requireDraft(GasSupplyPlan plan) {
        if (!Dict.GP_DRAFT.equals(plan.getStatus())) {
            throw new BizException("只有「草稿」状态的计划可以编辑，当前为「" + plan.getStatus()
                    + "」；已提交版本请复制新版本后修改");
        }
    }

    private boolean isReadonly(String status) {
        return Dict.GP_SUBMITTED.equals(status) || Dict.GP_FROZEN.equals(status);
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作人不能为空");
        }
    }

    /**
     * 行锁内复查状态：当前状态必须等于期望值才允许推进；
     * 若已被并发请求推进到 {@code conflictStatuses} 中的状态，后来者收到 409 明确冲突，
     * 不得改写配额、复核意见或状态；其他状态属非法跳转，返回 400 业务错误。
     */
    private GasSupplyPlan lockPlan(Long id, String expected, Set<String> conflictStatuses,
                                   String action) {
        GasSupplyPlan plan = planRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("供气计划不存在：" + id));
        if (expected.equals(plan.getStatus())) {
            return plan;
        }
        if (conflictStatuses.contains(plan.getStatus())) {
            throw new ConflictException("该计划刚被其他操作员推进到「" + plan.getStatus()
                    + "」，" + action + "冲突：您的本次操作未生效，已保存的配额与意见未被改写");
        }
        throw new BizException("非法状态跳转：当前为「" + plan.getStatus()
                + "」，不能执行" + action);
    }

    private GasSupplyPlan lockPlan(Long id, String expected, String action) {
        return lockPlan(id, expected, java.util.Set.of(), action);
    }

    private void recordHistory(GasSupplyPlan plan, String action, String from, String to,
                               String operator, String opinion, LocalDateTime now) {
        GasSupplyPlanHistory h = new GasSupplyPlanHistory();
        h.setPlanId(plan.getId());
        h.setAction(action);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setOperatorName(operator.trim());
        h.setReviewOpinion(opinion);
        h.setCreatedAt(now);
        historyRepository.save(h);
    }

    /** 同片区同日期下一版本号（调用方须先持有同日期版本序列锁）。 */
    private int nextVersionNumber(LocalDate date) {
        Integer max = planRepository.findMaxVersionByDate(date);
        return (max == null ? 0 : max) + 1;
    }

    private Map<String, GasSupplyQuota> keyByStation(List<GasSupplyQuota> rows) {
        Map<String, GasSupplyQuota> map = new LinkedHashMap<>();
        for (GasSupplyQuota row : rows) {
            map.put(row.getStationType() + "#" + row.getStationId(), row);
        }
        return map;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
