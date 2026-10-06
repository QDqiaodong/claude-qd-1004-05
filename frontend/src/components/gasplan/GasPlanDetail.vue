<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import GasPlanEditor from './GasPlanEditor.vue'
import { gpStatusTagType, stationTypeLabel, diffChangeTypeLabel } from '../../utils/gasPlan'

const props = defineProps({ planId: { type: Number, required: true } })
const emit = defineEmits(['changed', 'copied'])

const detail = ref(null)
const loading = ref(false)
const actionError = ref('')
const busy = ref(false)

async function load() {
  loading.value = true
  actionError.value = ''
  try {
    detail.value = await api.getGasPlan(props.planId)
  } catch (e) {
    actionError.value = e.message
  } finally {
    loading.value = false
  }
}

const plan = computed(() => detail.value?.plan)
const readonly = computed(() => ['已提交', '已冻结'].includes(plan.value?.status))
const blockCount = computed(() => detail.value?.blockCount || 0)
const gateQuotas = computed(() => detail.value?.gateQuotas || [])
const regulatorQuotas = computed(() => detail.value?.regulatorQuotas || [])
const histories = computed(() => detail.value?.histories || [])
const numOrZero = (v) => (v === null || v === undefined || v === '' ? 0 : Number(v))

function quotaProblems(q) {
  return (q.diffs || []).filter(d => d.level === 'BLOCK').map(d => d.message)
}

// ---------------- 编辑 ----------------
const editVisible = ref(false)
async function onEditDone() {
  editVisible.value = false
  ElMessage.success('草稿已更新')
  await load()
  emit('changed', true)
}

// ---------------- 动作（提交复核 / 冻结） ----------------
async function askOperator(actionLabel) {
  try {
    const { value } = await ElMessageBox.prompt('请输入操作人', actionLabel, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '如：计划员甲',
      inputValidator: (v) => (v && v.trim() ? true : '操作人不能为空')
    })
    return value.trim()
  } catch {
    return null
  }
}

async function submitReview() {
  actionError.value = ''
  if (blockCount.value > 0) {
    ElMessage.error('仍存在能力上限/需求来源拦截项，请先调整配额')
    return
  }
  try {
    await ElMessageBox.confirm(
      '提交后进入待复核；门站进站能力、调压站出口上限、负配额与需求来源将被再次校验，确认提交复核？',
      '提交复核', { type: 'warning' })
  } catch { return }
  const operator = await askOperator('提交复核')
  if (!operator) return
  busy.value = true
  try {
    await api.gasPlanAction(props.planId, 'submit-review', { operator })
    ElMessage.success('已提交复核')
    await load()
    emit('changed', true)
  } catch (e) {
    // 含并发 409 冲突：明确提示，本地配额/意见不被改写
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

async function freeze() {
  actionError.value = ''
  try {
    await ElMessageBox.confirm('冻结后版本终态归档，历史分配与复核意见永久可查，确认冻结？',
      '冻结计划', { type: 'warning' })
  } catch { return }
  const operator = await askOperator('冻结')
  if (!operator) return
  busy.value = true
  try {
    await api.gasPlanAction(props.planId, 'freeze', { operator })
    ElMessage.success('计划已冻结')
    await load()
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 复核（通过/退回） ----------------
const reviewVisible = ref(false)
const reviewForm = ref({ operator: '', passed: true, reviewOpinion: '' })
function openReview(passed) {
  reviewForm.value = { operator: '', passed, reviewOpinion: '' }
  reviewVisible.value = true
}
async function submitReviewOpinion() {
  actionError.value = ''
  if (!reviewForm.value.operator.trim()) {
    ElMessage.error('复核人不能为空')
    return
  }
  if (!reviewForm.value.passed && !reviewForm.value.reviewOpinion.trim()) {
    ElMessage.error('复核退回时必须填写退回意见')
    return
  }
  busy.value = true
  try {
    await api.gasPlanAction(props.planId, 'review', {
      operator: reviewForm.value.operator.trim(),
      passed: reviewForm.value.passed,
      reviewOpinion: reviewForm.value.reviewOpinion.trim() || null
    })
    ElMessage.success(reviewForm.value.passed ? '复核通过，计划已提交' : '已退回草稿')
    reviewVisible.value = false
    await load()
    emit('changed', true)
  } catch (e) {
    // 并发复核冲突：提示明确冲突，已填复核意见保留在对话框里，不覆盖先到者
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 复制新版本 ----------------
const copyVisible = ref(false)
const copyForm = ref({ operator: '', changeNote: '', newPlanNo: '' })
function openCopy() {
  copyForm.value = { operator: '', changeNote: '', newPlanNo: '' }
  copyVisible.value = true
}
async function submitCopy() {
  actionError.value = ''
  if (!copyForm.value.operator.trim()) {
    ElMessage.error('操作人不能为空')
    return
  }
  if (!copyForm.value.changeNote.trim()) {
    ElMessage.error('复制新版本时必须填写变更说明')
    return
  }
  busy.value = true
  try {
    const created = await api.gasPlanAction(props.planId, 'copy', {
      operator: copyForm.value.operator.trim(),
      changeNote: copyForm.value.changeNote.trim(),
      newPlanNo: copyForm.value.newPlanNo.trim() || null
    })
    ElMessage.success(`已复制生成 V${created.planVersion}（草稿）`)
    copyVisible.value = false
    emit('copied', created.id)
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 版本对比 ----------------
const compareTargetId = ref(null)
const compareData = ref(null)
const compareError = ref('')
const siblingPlans = ref([])

async function loadSiblings() {
  if (!plan.value?.planDate) return
  try {
    const data = await api.listGasPlans({ planDate: plan.value.planDate, page: 0, size: 100 })
    siblingPlans.value = (data.items || []).filter(p => p.id !== props.planId)
  } catch {
    siblingPlans.value = []
  }
}

async function runCompare() {
  compareError.value = ''
  compareData.value = null
  if (!compareTargetId.value) {
    ElMessage.warning('请先选择要对比的版本')
    return
  }
  try {
    compareData.value = await api.compareGasPlans(props.planId, compareTargetId.value)
  } catch (e) {
    compareError.value = e.message
  }
}

function deltaTagType(delta) {
  const n = Number(delta)
  if (n > 0) return 'danger'
  if (n < 0) return 'success'
  return 'info'
}

onMounted(async () => {
  await load()
  await loadSiblings()
})
</script>

<template>
  <div v-loading="loading" class="gp-detail">
    <div v-if="actionError" class="err conflict-err">{{ actionError }}</div>

    <template v-if="plan">
      <el-descriptions :column="3" border size="small" class="gp-meta">
        <el-descriptions-item label="计划编号">{{ plan.planNo }}</el-descriptions-item>
        <el-descriptions-item label="计划日期">{{ plan.planDate }}</el-descriptions-item>
        <el-descriptions-item label="版本">
          V{{ plan.planVersion }}
          <el-tag size="small" :type="gpStatusTagType(plan.status)" style="margin-left:6px">
            {{ plan.status }}
          </el-tag>
          <el-tag v-if="plan.copiedFromId" size="small" effect="plain" style="margin-left:4px">
            复制自 #{{ plan.copiedFromId }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="编制人">{{ plan.ownerName }}</el-descriptions-item>
        <el-descriptions-item label="片区日需求">{{ plan.dailyDemandM3 }} m³</el-descriptions-item>
        <el-descriptions-item label="需求来源">{{ plan.demandSource || '—' }}</el-descriptions-item>
        <el-descriptions-item label="提交">{{ plan.submittedAt || '—' }}
          <template v-if="plan.submittedBy">（{{ plan.submittedBy }}）</template>
        </el-descriptions-item>
        <el-descriptions-item label="复核">{{ plan.reviewedAt || '—' }}
          <template v-if="plan.reviewedBy">（{{ plan.reviewedBy }}）</template>
        </el-descriptions-item>
        <el-descriptions-item label="冻结">{{ plan.frozenAt || '—' }}
          <template v-if="plan.frozenBy">（{{ plan.frozenBy }}）</template>
        </el-descriptions-item>
        <el-descriptions-item label="变更说明" :span="3">{{ plan.changeNote || '—' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 环节操作 -->
      <div class="action-bar">
        <el-button v-if="plan.status === '草稿'" size="small" @click="editVisible = true">编辑配额</el-button>
        <el-button v-if="plan.status === '草稿'" size="small" type="primary" :loading="busy"
          :disabled="blockCount > 0" @click="submitReview">提交复核</el-button>
        <el-button v-if="plan.status === '待复核'" size="small" type="success" :loading="busy"
          @click="openReview(true)">复核通过</el-button>
        <el-button v-if="plan.status === '待复核'" size="small" type="warning" :loading="busy"
          @click="openReview(false)">复核退回</el-button>
        <el-button v-if="plan.status === '已提交'" size="small" type="primary" :loading="busy"
          @click="freeze">冻结</el-button>
        <el-button v-if="readonly" size="small" type="warning" plain @click="openCopy">
          复制生成新版本
        </el-button>
        <span v-if="plan.status === '草稿' && blockCount > 0" class="action-hint danger-text">
          存在 {{ blockCount }} 项提交拦截项，请先在「编辑配额」中处理
        </span>
      </div>

      <!-- 配额明细：门站 -->
      <div class="section-title">门站配额 · {{ gateQuotas.length }} 站
        <span class="section-sub">（合计 {{ numOrZero(detail.gateQuotaTotal) }} / 进站能力 {{ numOrZero(detail.gateCapacityTotal) }} m³）</span>
      </div>
      <el-table :data="gateQuotas" border size="small">
        <el-table-column prop="stationCode" label="编号" width="120" />
        <el-table-column prop="stationName" label="门站" min-width="150" show-overflow-tooltip />
        <el-table-column label="进站能力" width="110">
          <template #default="{ row }">{{ row.capacityM3 }}</template>
        </el-table-column>
        <el-table-column prop="quotaM3" label="配额(m³)" width="110" />
        <el-table-column label="档案现状" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.present === false" size="small" type="danger">缺失/跨片区</el-tag>
            <el-tag v-else-if="row.currentStatus" size="small"
              :type="row.currentStatus === '运行' ? 'success' : 'warning'">{{ row.currentStatus }}</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="校验" min-width="200">
          <template #default="{ row }">
            <span v-if="quotaProblems(row).length" class="danger-text">
              {{ quotaProblems(row).join('；') }}
            </span>
            <span v-else class="ok-text">未超进站能力</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 配额明细：调压站 -->
      <div class="section-title">调压站配额 · {{ regulatorQuotas.length }} 站
        <span class="section-sub">（合计 {{ numOrZero(detail.regulatorQuotaTotal) }} / 出口能力 {{ numOrZero(detail.regulatorCapacityTotal) }} m³）</span>
      </div>
      <el-table :data="regulatorQuotas" border size="small">
        <el-table-column prop="stationCode" label="编号" width="120" />
        <el-table-column prop="stationName" label="调压站" min-width="150" show-overflow-tooltip />
        <el-table-column label="出口上限" width="110">
          <template #default="{ row }">{{ row.capacityM3 }}</template>
        </el-table-column>
        <el-table-column prop="quotaM3" label="配额(m³)" width="110" />
        <el-table-column label="档案现状" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.present === false" size="small" type="danger">缺失/跨片区</el-tag>
            <el-tag v-else-if="row.currentStatus" size="small"
              :type="row.currentStatus === '运行' ? 'success' : 'warning'">{{ row.currentStatus }}</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="校验" min-width="200">
          <template #default="{ row }">
            <span v-if="quotaProblems(row).length" class="danger-text">
              {{ quotaProblems(row).join('；') }}
            </span>
            <span v-else class="ok-text">未超出口上限</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 非拦截提示（档案变化等，不改变已保存配额） -->
      <template v-for="q in [...gateQuotas, ...regulatorQuotas]" :key="'w-' + q.id">
        <div v-for="d in (q.diffs || []).filter(x => x.level === 'WARN')" :key="q.id + d.kind"
          class="warn-line">
          <el-tag size="small" type="warning" effect="plain">{{ stationTypeLabel(q.stationType) }}</el-tag>
          <b>{{ q.stationCode }}</b> {{ d.message }}
        </div>
      </template>

      <!-- 流转记录与复核意见（已冻结版本同样完整可查） -->
      <div class="section-title">流转记录与复核意见 · {{ histories.length }} 条
        <span class="section-sub">（只追加留痕，已冻结版本永久可查）</span>
      </div>
      <el-timeline class="history-line">
        <el-timeline-item v-for="h in [...histories].reverse()" :key="h.id"
          :timestamp="h.createdAt" placement="top">
          <el-tag size="small">{{ h.action }}</el-tag>
          <span class="history-operator">{{ h.operatorName }}</span>
          <span v-if="h.fromStatus" class="history-flow">
            {{ h.fromStatus }} → {{ h.toStatus }}
          </span>
          <div v-if="h.reviewOpinion" class="history-opinion">意见：{{ h.reviewOpinion }}</div>
        </el-timeline-item>
      </el-timeline>

      <!-- 版本对比 -->
      <div class="section-title">版本对比</div>
      <div class="compare-bar">
        <el-select v-model="compareTargetId" placeholder="选择同日期版本" clearable
          style="width:280px">
          <el-option v-for="p in siblingPlans" :key="p.id"
            :label="`${p.planNo} V${p.planVersion}（${p.status}）`" :value="p.id" />
        </el-select>
        <el-button size="small" @click="runCompare">对比</el-button>
        <span v-if="!siblingPlans.length" class="action-hint">同日期暂无其他版本可对比</span>
      </div>
      <div v-if="compareError" class="err">{{ compareError }}</div>
      <template v-if="compareData">
        <el-descriptions :column="2" border size="small" class="compare-meta">
          <el-descriptions-item label="来源版本">
            {{ compareData.fromPlanNo }} V{{ compareData.fromVersion }}（{{ compareData.fromStatus }}）
          </el-descriptions-item>
          <el-descriptions-item label="对比版本">
            {{ compareData.toPlanNo }} V{{ compareData.toVersion }}（{{ compareData.toStatus }}）
          </el-descriptions-item>
          <el-descriptions-item label="片区需求差异">
            <el-tag size="small" :type="deltaTagType(compareData.demandDelta)">
              {{ compareData.demandDelta }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="门站配额合计差异">
            <el-tag size="small" :type="deltaTagType(compareData.gateTotalDelta)">
              {{ compareData.gateTotalDelta }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="调压站配额合计差异">
            <el-tag size="small" :type="deltaTagType(compareData.regulatorTotalDelta)">
              {{ compareData.regulatorTotalDelta }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="compareData.quotaDiffs" border size="small">
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag size="small">{{ stationTypeLabel(row.stationType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="stationCode" label="编号" width="120" />
          <el-table-column prop="stationName" label="站点" min-width="140" show-overflow-tooltip />
          <el-table-column label="变化" width="92">
            <template #default="{ row }">
              <el-tag size="small"
                :type="row.changeType === 'SAME' ? 'info'
                  : row.changeType === 'ADDED' ? 'warning'
                  : row.changeType === 'REMOVED' ? 'danger' : 'primary'">
                {{ diffChangeTypeLabel(row.changeType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原配额" width="110">
            <template #default="{ row }">{{ row.fromQuota ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="新配额" width="110">
            <template #default="{ row }">{{ row.toQuota ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="差异" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="deltaTagType(row.quotaDelta)">{{ row.quotaDelta }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </template>

    <!-- 复核 -->
    <el-dialog v-model="reviewVisible"
      :title="reviewForm.passed ? '复核通过' : '复核退回'" width="480px"
      :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="复核人" required>
          <el-input v-model="reviewForm.operator" placeholder="复核负责人" />
        </el-form-item>
        <el-form-item :label="reviewForm.passed ? '复核意见' : '退回意见'" :required="!reviewForm.passed">
          <el-input v-model="reviewForm.reviewOpinion" type="textarea" :rows="3"
            :placeholder="reviewForm.passed ? '选填' : '退回必须说明原因，编制人修改后重新提交'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button :type="reviewForm.passed ? 'success' : 'warning'" :loading="busy"
          @click="submitReviewOpinion">提交复核结论</el-button>
      </template>
    </el-dialog>

    <!-- 复制新版本 -->
    <el-dialog v-model="copyVisible" title="复制生成新版本" width="480px"
      :close-on-click-modal="false">
      <el-form label-width="92px">
        <el-form-item label="操作人" required>
          <el-input v-model="copyForm.operator" placeholder="新版本编制人" />
        </el-form-item>
        <el-form-item label="变更说明" required>
          <el-input v-model="copyForm.changeNote" type="textarea" :rows="3"
            placeholder="说明与已提交版本相比的变更点" />
        </el-form-item>
        <el-form-item label="新计划编号">
          <el-input v-model="copyForm.newPlanNo" placeholder="留空则自动派生（原编号-V版本号）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitCopy">复制为草稿新版本</el-button>
      </template>
    </el-dialog>

    <GasPlanEditor v-if="editVisible && plan" :plan="plan"
      @done="onEditDone" @cancel="editVisible = false" />
  </div>
</template>

<style scoped>
.conflict-err { font-weight: 600 }
.action-bar {
  display: flex; align-items: center; gap: 8px;
  margin: 12px 0; flex-wrap: wrap;
}
.action-hint { font-size: 12px; color: var(--el-text-color-secondary) }
.danger-text { color: var(--el-color-danger); font-size: 12px }
.ok-text { color: var(--el-color-success); font-size: 12px }
.section-title {
  font-weight: 600; margin: 14px 0 8px; font-size: 14px;
}
.section-sub { font-weight: 400; color: var(--el-text-color-secondary); font-size: 12px }
.warn-line {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--el-color-warning-dark-2);
  line-height: 1.9; flex-wrap: wrap;
}
.history-line { margin: 8px 0 0 8px }
.history-operator { margin: 0 8px 0 8px; font-weight: 600 }
.history-flow { color: var(--el-text-color-secondary); font-size: 12px }
.history-opinion {
  margin-top: 2px; padding: 4px 8px;
  background: var(--el-fill-color-light); border-radius: 4px;
  font-size: 12px;
}
.compare-bar { display: flex; gap: 8px; margin-bottom: 10px }
.compare-meta { margin-bottom: 10px }
</style>
