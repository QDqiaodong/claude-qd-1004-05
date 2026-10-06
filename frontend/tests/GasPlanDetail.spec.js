import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import GasPlanDetail from '../src/components/gasplan/GasPlanDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    getGasPlan: vi.fn(),
    gasPlanAction: vi.fn(),
    compareGasPlans: vi.fn(),
    listGasPlans: vi.fn().mockResolvedValue({ items: [] }),
    updateGasPlan: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function detailData(overrides = {}) {
  return {
    plan: {
      id: 1, planNo: 'GP-1', planDate: '2026-10-20', planVersion: 1, copiedFromId: null,
      ownerName: '计划员甲', dailyDemandM3: 130000, demandSource: '调度预测', changeNote: '初排',
      status: '草稿', submittedAt: null, submittedBy: null, reviewedAt: null, reviewedBy: null,
      frozenAt: null, frozenBy: null
    },
    gateQuotas: [],
    regulatorQuotas: [],
    histories: [],
    gateQuotaTotal: 0, gateCapacityTotal: 0, regulatorQuotaTotal: 0, regulatorCapacityTotal: 0,
    blockCount: 0, warnCount: 0,
    ...overrides
  }
}

function mountDetail() {
  return mount(GasPlanDetail, {
    props: { planId: 1 },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

function dialog(root, text) {
  const scope = root instanceof Element ? root : root.element
  const all = [...Array.from(scope.querySelectorAll('.el-dialog')),
    ...Array.from(document.body.querySelectorAll('.el-dialog'))]
  return all.find(d => d.textContent.includes(text))
}

function button(root, text) {
  const scope = root instanceof Element ? root : root.element
  const all = [...Array.from(scope.querySelectorAll('button')),
    ...Array.from(document.body.querySelectorAll('button'))]
  return all.find(b => b.textContent.includes(text))
}

function setInput(dlg, text) {
  const input = dlg.querySelector('input')
  input.value = text
  input.dispatchEvent(new Event('input'))
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('提交复核与能力拦截', () => {
  it('草稿存在拦截项时禁用提交复核按钮', async () => {
    api.getGasPlan.mockResolvedValue(detailData({ blockCount: 2 }))
    const wrapper = mountDetail()
    await flushPromises()
    const btn = button(wrapper, '提交复核')
    expect(btn.attributes).toBeDefined()
    expect(btn.hasAttribute('disabled')).toBe(true)
    expect(wrapper.text()).toContain('2 项提交拦截项')
  })

  it('提交复核请求返回冲突时，展示明确冲突反馈且不改本地状态', async () => {
    api.getGasPlan.mockResolvedValue(detailData())
    const wrapper = mountDetail()
    await flushPromises()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '计划员乙' })
    api.gasPlanAction.mockRejectedValueOnce(
      new Error('该计划刚被其他操作员推进到「待复核」，提交复核冲突：您的本次操作未生效，已保存的配额与意见未被改写'))

    await wrapper.vm.submitReview()
    await flushPromises()

    expect(api.gasPlanAction).toHaveBeenCalledWith(1, 'submit-review', { operator: '计划员乙' })
    expect(wrapper.text()).toContain('提交复核冲突')
    expect(wrapper.text()).toContain('已保存的配额与意见未被改写')
  })
})

describe('复核通过 / 退回', () => {
  it('待复核可通过；退回意见为空时前端拦截', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      plan: { ...detailData().plan, status: '待复核' }
    }))
    api.gasPlanAction.mockResolvedValue({ status: '已提交' })
    const wrapper = mountDetail()
    await flushPromises()

    // 打开退回对话框
    button(wrapper, '复核退回').click()
    await flushPromises()
    const rejectDlg = dialog(wrapper.element, '复核退回')
    const inputs = rejectDlg.querySelectorAll('input')
    inputs[0].value = '复核员甲'
    inputs[0].dispatchEvent(new Event('input'))
    // 意见留空直接提交
    Array.from(rejectDlg.querySelectorAll('button'))
      .find(b => b.textContent.includes('提交复核结论')).click()
    await flushPromises()
    expect(api.gasPlanAction).not.toHaveBeenCalled()

    // 通过路径：填复核人，调 review 接口
    button(wrapper, '复核通过').click()
    await flushPromises()
    const passDlg = dialog(wrapper.element, '复核通过')
    const passInputs = passDlg.querySelectorAll('input')
    passInputs[0].value = '复核员乙'
    passInputs[0].dispatchEvent(new Event('input'))
    Array.from(passDlg.querySelectorAll('button'))
      .find(b => b.textContent.includes('提交复核结论')).click()
    await flushPromises()

    expect(api.gasPlanAction).toHaveBeenCalledWith(1, 'review',
      { operator: '复核员乙', passed: true, reviewOpinion: null })
  })
})

describe('已提交/冻结：复制新版本', () => {
  it('已冻结版本展示历史分配与复核意见，复制必填变更说明并打开新版本', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      plan: { ...detailData().plan, status: '已冻结', planVersion: 1,
        frozenAt: '2026-10-20T18:00', frozenBy: '调度主管', reviewedBy: '复核员乙' },
      gateQuotas: [{
        id: 1, stationType: 'GATE', stationId: 1, stationCode: 'GS-01', stationName: '城东门站',
        capacityM3: 100000, quotaM3: 80000, present: true, currentStatus: '运行', diffs: []
      }],
      gateQuotaTotal: 80000, gateCapacityTotal: 100000,
      histories: [
        { id: 1, action: '提交复核', fromStatus: '草稿', toStatus: '待复核', operatorName: '计划员甲' },
        { id: 2, action: '复核通过', fromStatus: '待复核', toStatus: '已提交',
          operatorName: '复核员乙', reviewOpinion: '同意', createdAt: '2026-10-20T10:00' },
        { id: 3, action: '冻结', fromStatus: '已提交', toStatus: '已冻结', operatorName: '调度主管' }
      ]
    }))
    api.gasPlanAction.mockResolvedValue({ id: 9, planVersion: 2, status: '草稿' })
    const wrapper = mountDetail()
    await flushPromises()

    // 历史分配与复核意见可查
    expect(wrapper.text()).toContain('80000')
    expect(wrapper.text()).toContain('复核通过')
    expect(wrapper.text()).toContain('同意')
    expect(wrapper.text()).toContain('冻结')

    // 复制对话框：变更说明为空时拦截
    button(wrapper, '复制生成新版本').click()
    await flushPromises()
    const copyDlg = dialog(wrapper.element, '复制生成新版本')
    const inputs = copyDlg.querySelectorAll('input')
    inputs[0].value = '计划员甲'
    inputs[0].dispatchEvent(new Event('input'))
    Array.from(copyDlg.querySelectorAll('button'))
      .find(b => b.textContent.includes('复制为草稿新版本')).click()
    await flushPromises()
    expect(api.gasPlanAction).not.toHaveBeenCalled()

    // 填变更说明后复制成功，并抛出 copied 事件
    const textareas = copyDlg.querySelectorAll('textarea')
    textareas[0].value = '次日大用户增产调增 5000'
    textareas[0].dispatchEvent(new Event('input'))
    await flushPromises()
    Array.from(copyDlg.querySelectorAll('button'))
      .find(b => b.textContent.includes('复制为草稿新版本')).click()
    await flushPromises()

    expect(api.gasPlanAction).toHaveBeenCalledWith(1, 'copy',
      { operator: '计划员甲', changeNote: '次日大用户增产调增 5000', newPlanNo: null })
    const emitted = wrapper.emitted('copied')
    expect(emitted[0]).toEqual([9])
  })
})

describe('版本对比', () => {
  it('列出同日期其他版本，选择后渲染新增/变化/删除与合计差异', async () => {
    api.getGasPlan.mockResolvedValue(detailData({
      plan: { ...detailData().plan, status: '已提交' }
    }))
    api.listGasPlans.mockResolvedValue({
      items: [
        { id: 1, planNo: 'GP-1', planVersion: 1, status: '已提交' },
        { id: 2, planNo: 'GP-1-V2', planVersion: 2, status: '草稿' }
      ]
    })
    api.compareGasPlans.mockResolvedValue({
      fromPlanId: 1, toPlanId: 2, fromPlanNo: 'GP-1', toPlanNo: 'GP-1-V2',
      fromVersion: 1, toVersion: 2, fromStatus: '已提交', toStatus: '草稿',
      fromDemand: 130000, toDemand: 135000, demandDelta: 5000,
      fromGateTotal: 80000, toGateTotal: 85000, gateTotalDelta: 5000,
      fromRegulatorTotal: 40000, toRegulatorTotal: 40000, regulatorTotalDelta: 0,
      quotaDiffs: [
        { stationType: 'GATE', stationId: 1, stationCode: 'GS-01', stationName: '城东门站',
          changeType: 'CHANGED', fromQuota: 80000, toQuota: 85000, quotaDelta: 5000,
          fromCapacity: 100000, toCapacity: 100000 },
        { stationType: 'REGULATOR', stationId: 11, stationCode: 'RS-01', stationName: '兴业调压站',
          changeType: 'REMOVED', fromQuota: 40000, toQuota: null, quotaDelta: -40000,
          fromCapacity: 50000, toCapacity: 50000 }
      ]
    })
    const wrapper = mountDetail()
    await flushPromises()

    // 同日期版本列表只用于对比，且排除当前版本
    expect(api.listGasPlans).toHaveBeenCalledWith(
      expect.objectContaining({ planDate: '2026-10-20' }))
    expect(wrapper.vm.siblingPlans.map(p => p.id)).toEqual([2])
    expect(wrapper.vm.siblingPlans[0]).toMatchObject({ planNo: 'GP-1-V2', planVersion: 2, status: '草稿' })

    wrapper.vm.compareTargetId = 2
    button(wrapper, '对比').click()
    await flushPromises()
    await flushPromises()

    expect(api.compareGasPlans).toHaveBeenCalledWith(1, 2)
    expect(wrapper.text()).toContain('GP-1 V1')
    expect(wrapper.text()).toContain('GP-1-V2 V2')
    expect(wrapper.text()).toContain('配额变化')
    expect(wrapper.text()).toContain('删除')
    expect(wrapper.text()).toContain('5000')
    expect(wrapper.text()).toContain('-40000')
  })
})
