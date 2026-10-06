<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { stationTypeLabel, summarizeValidation, validateQuota } from '../../utils/gasPlan'

// 供气计划创建 / 草稿编辑对话框：
// 按日期读取当前片区门站日输气量（进站能力）与调压站出口能力，把配额拆分到各站；
// 配额超能力 / 为负 / 缺需求来源即时红字提示。提交失败（如并发冲突）表单内容全部保留。
const props = defineProps({
  // 传入 plan 表示编辑既有草稿（含 id/日期）；null 表示新建
  plan: { type: Object, default: null },
  // 新建时从列表带入选中的日期
  initialDate: { type: String, default: '' }
})
const emit = defineEmits(['done', 'cancel'])

const capability = ref({ gateStations: [], regulatorStations: [] })
const submitting = ref(false)
const errorMsg = ref('')

const form = reactive(blankForm())

function blankForm() {
  return {
    planNo: '',
    planDate: '',
    ownerName: '',
    dailyDemandM3: '',
    demandSource: '',
    changeNote: '',
    quotas: []
  }
}

onMounted(async () => {
  let planDate = props.initialDate
  if (props.plan) {
    try {
      const detail = await api.getGasPlan(props.plan.id)
      const p = detail.plan
      form.planNo = p.planNo
      form.planDate = p.planDate
      form.ownerName = p.ownerName
      form.dailyDemandM3 = p.dailyDemandM3
      form.demandSource = p.demandSource || ''
      form.changeNote = p.changeNote || ''
      form.quotas = [...(detail.gateQuotas || []), ...(detail.regulatorQuotas || [])].map(q => ({
        stationType: q.stationType,
        stationId: q.stationId,
        stationCode: q.stationCode,
        quotaM3: q.quotaM3,
        remark: q.remark || ''
      }))
      planDate = p.planDate
    } catch (e) {
      errorMsg.value = e.message
    }
  } else if (planDate) {
    form.planDate = planDate
  }
  if (planDate) {
    try {
      capability.value = await api.gasPlanCapability(planDate)
    } catch (e) {
      errorMsg.value = e.message
    }
  }
})

async function changeDate() {
  if (!form.planDate) {
    capability.value = { gateStations: [], regulatorStations: [] }
    return
  }
  try {
    capability.value = await api.gasPlanCapability(form.planDate)
  } catch (e) {
    errorMsg.value = e.message
  }
}

const gateOptions = computed(() => capability.value.gateStations || [])
const regulatorOptions = computed(() => capability.value.regulatorStations || [])

const summary = computed(() => summarizeValidation(form, capability.value))

function capacityOf(row) {
  const list = row.stationType === 'GATE' ? gateOptions.value : regulatorOptions.value
  return list.find(s => s.id === row.stationId)
}

function rowProblems(row) {
  const station = capacityOf(row)
  if (!station) return ['站点不在当前片区档案中']
  const cap = row.stationType === 'GATE'
    ? station.dailyFlowM3
    : station.outletCapacityM3
  return validateQuota(row, cap ?? null)
}

function addQuota(type) {
  form.quotas.push({ stationType: type, stationId: null, stationCode: '', quotaM3: '', remark: '' })
}
function removeQuota(idx) {
  form.quotas.splice(idx, 1)
}
function onStationPick(row) {
  const station = capacityOf(row)
  row.stationCode = station ? station.stationCode : ''
}

async function submit() {
  errorMsg.value = ''
  if (!form.planNo.trim()) { errorMsg.value = '计划编号不能为空'; return }
  if (!form.planDate) { errorMsg.value = '请选择计划日期'; return }
  if (!form.ownerName.trim()) { errorMsg.value = '编制人不能为空'; return }
  if (form.dailyDemandM3 === '' || Number(form.dailyDemandM3) < 0) {
    errorMsg.value = '片区日需求量不能为空且不能为负'
    return
  }
  if (!form.quotas.length) { errorMsg.value = '请至少拆分一条门站或调压站配额'; return }
  for (const [idx, q] of form.quotas.entries()) {
    if (!q.stationId) { errorMsg.value = `第 ${idx + 1} 行配额未选择站点`; return }
  }
  // 能力 / 负配额 / 缺需求来源：前端先拦一次（后端提交复核时仍会权威校验）
  if (summary.value.blocks.length) {
    errorMsg.value = '配额校验未通过：' + summary.value.blocks[0]
    return
  }

  const payload = {
    planNo: form.planNo.trim(),
    planDate: form.planDate,
    ownerName: form.ownerName.trim(),
    dailyDemandM3: Number(form.dailyDemandM3),
    demandSource: form.demandSource.trim() || null,
    changeNote: form.changeNote.trim() || null,
    quotas: form.quotas.map(q => ({
      stationType: q.stationType,
      stationId: q.stationId,
      quotaM3: Number(q.quotaM3),
      remark: q.remark ? String(q.remark).trim() : null
    }))
  }
  submitting.value = true
  try {
    if (props.plan) {
      await api.updateGasPlan(props.plan.id, payload)
      ElMessage.success('草稿配额已更新')
    } else {
      await api.createGasPlan(payload)
      ElMessage.success(`供气计划 ${payload.planNo} 已创建（草稿）`)
    }
    emit('done')
  } catch (e) {
    // 校验失败/冲突只提示，表单内容全部保留
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    model-value
    :title="plan ? '编辑供气计划草稿' : '编制供气计划'"
    width="860px" :close-on-click-modal="false" @close="emit('cancel')">
    <div v-if="errorMsg" class="err">{{ errorMsg }}</div>
    <el-form :model="form" label-width="110px" class="dialog-form">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="计划编号" required>
            <el-input v-model="form.planNo" placeholder="租户内唯一，如 GP-20261020-01" :disabled="!!plan" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="计划日期" required>
            <el-date-picker v-model="form.planDate" type="date" value-format="YYYY-MM-DD"
              placeholder="选择供气日期" style="width:100%" :disabled="!!plan" @change="changeDate" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="编制人" required>
            <el-input v-model="form.ownerName" placeholder="计划编制人" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="片区日需求" required>
            <el-input v-model="form.dailyDemandM3" type="number" placeholder="m³">
              <template #append>m³</template>
            </el-input>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="需求来源">
            <el-input v-model="form.demandSource" placeholder="调度预测/历史同期/大用户申报" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="变更说明">
            <el-input v-model="form.changeNote" placeholder="退回修改/复制版本时说明变更点" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="配额拆分" required>
        <div class="quota-wrap">
          <div class="quota-toolbar">
            <el-button size="small" @click="addQuota('GATE')" :disabled="!form.planDate">+ 门站配额</el-button>
            <el-button size="small" @click="addQuota('REGULATOR')" :disabled="!form.planDate">+ 调压站配额</el-button>
            <span class="field-hint">门站配额不得超过进站能力，调压站配额不得超过出口上限</span>
          </div>

          <el-table :data="form.quotas" border size="small">
            <el-table-column label="类型" width="86">
              <template #default="{ row }">
                <el-tag size="small">{{ stationTypeLabel(row.stationType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="站点" min-width="190">
              <template #default="{ row }">
                <el-select v-model="row.stationId" size="small" placeholder="选择当前片区站点"
                  style="width:100%" @change="onStationPick(row)">
                  <el-option v-for="s in (row.stationType === 'GATE' ? gateOptions : regulatorOptions)"
                    :key="s.id"
                    :label="`${s.stationCode} ${s.stationName}（${s.status}）`" :value="s.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="能力上限" width="120">
              <template #default="{ row }">
                <span v-if="capacityOf(row)">
                  {{ row.stationType === 'GATE'
                    ? capacityOf(row).dailyFlowM3
                    : (capacityOf(row).outletCapacityM3 ?? '未核定') }}
                </span>
                <span v-else class="danger-text">—</span>
              </template>
            </el-table-column>
            <el-table-column label="配额(m³)" width="140">
              <template #default="{ row }">
                <el-input v-model="row.quotaM3" type="number" size="small" placeholder="0"
                  :class="{ 'quota-invalid': rowProblems(row).length }" />
              </template>
            </el-table-column>
            <el-table-column label="校验提示" min-width="180">
              <template #default="{ row }">
                <span v-if="rowProblems(row).length" class="danger-text">
                  {{ rowProblems(row).join('；') }}
                </span>
                <span v-else class="ok-text">满足能力约束</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="64">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="removeQuota($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="quota-totals">
            <span>门站配额合计 <b>{{ summary.gateQuotaTotal }}</b> / 进站能力 {{ summary.gateCapacityTotal }} m³</span>
            <span>调压站配额合计 <b>{{ summary.regQuotaTotal }}</b> / 出口能力 {{ summary.regCapacityTotal }} m³</span>
          </div>

          <el-alert v-for="(msg, i) in summary.blocks" :key="'b' + i" :closable="false" type="error"
            show-icon :title="msg" class="quota-alert" />
          <el-alert v-for="(msg, i) in summary.warns" :key="'w' + i" :closable="false" type="warning"
            show-icon :title="msg" class="quota-alert" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ plan ? '保存草稿' : '创建草稿' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.quota-wrap { width: 100% }
.quota-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px }
.field-hint { font-size: 12px; color: var(--el-text-color-secondary) }
.quota-totals {
  display: flex; gap: 20px; margin: 8px 0;
  font-size: 13px; color: var(--el-text-color-regular);
}
.quota-totals b { color: var(--primary-deep) }
.quota-alert { margin: 6px 0 }
.quota-invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.danger-text { color: var(--el-color-danger); font-size: 12px }
.ok-text { color: var(--el-color-success); font-size: 12px }
</style>
