<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import GasPlanEditor from '../components/gasplan/GasPlanEditor.vue'
import GasPlanDetail from '../components/gasplan/GasPlanDetail.vue'
import { GP_STATUS, gpStatusTagType } from '../utils/gasPlan'
import { useGasPlanList } from '../utils/useGasPlanList'

const props = defineProps({ tenant: String })

const { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage } =
  useGasPlanList()

watch(error, (msg) => { if (msg) ElMessage.error(msg) })

// ---------------- 详情抽屉 ----------------
const detailVisible = ref(false)
const detailId = ref(null)
function openDetail(row) {
  detailId.value = row.id
  detailVisible.value = true
}
function closeDetail(changed) {
  detailVisible.value = false
  if (changed) load()
}
async function onCopied(newId) {
  detailVisible.value = false
  await load()
  detailId.value = newId
  detailVisible.value = true
}

// ---------------- 新建 ----------------
const createVisible = ref(false)
async function onCreated() {
  createVisible.value = false
  await load()
}

watch(() => props.tenant, () => { resetPage() })
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">门站日输气计划 · 供气配额编排</span>
        <span class="panel-desc">草稿 → 待复核 → 已提交 → 已冻结（提交后只能复制新版本）</span>
      </div>

      <div class="toolbar">
        <el-date-picker v-model="query.planDate" type="date" value-format="YYYY-MM-DD"
          placeholder="计划日期" clearable style="width:160px" @change="search" />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width:130px"
          @change="search">
          <el-option v-for="s in GP_STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="search">查询</el-button>
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">编制供气计划</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="planNo" label="计划编号" width="180" />
        <el-table-column prop="planDate" label="计划日期" width="120" />
        <el-table-column label="版本" width="80">
          <template #default="{ row }">V{{ row.planVersion }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="gpStatusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="编制人" width="100" />
        <el-table-column prop="dailyDemandM3" label="日需求(m³)" width="120" />
        <el-table-column prop="demandSource" label="需求来源" min-width="130" show-overflow-tooltip />
        <el-table-column prop="reviewedBy" label="复核人" width="100" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openDetail(row)">详情/处置</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-sizes="[10, 20, 50]"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </div>

    <GasPlanEditor v-if="createVisible" :plan="null"
      @done="onCreated" @cancel="createVisible = false" />

    <el-drawer v-model="detailVisible" title="供气计划详情 · 配额 / 复核 / 版本对比" size="72%">
      <GasPlanDetail v-if="detailVisible" :plan-id="detailId"
        @changed="closeDetail" @copied="onCopied" />
    </el-drawer>
  </div>
</template>
