import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import GasPlanEditor from '../src/components/gasplan/GasPlanEditor.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    gasPlanCapability: vi.fn(),
    createGasPlan: vi.fn(),
    updateGasPlan: vi.fn(),
    getGasPlan: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function mountEditor(props = {}) {
  return mount(GasPlanEditor, {
    props: { plan: null, initialDate: '2026-10-20', ...props },
    attachTo: document.body,
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

function findButton(root, text) {
  const scope = root instanceof Element ? root : root.element
  const scoped = scope && typeof scope.querySelectorAll === 'function'
    ? Array.from(scope.querySelectorAll('button')) : []
  const all = [...scoped, ...Array.from(document.body.querySelectorAll('button'))]
  return all.find(b => b.textContent.includes(text))
}

beforeEach(() => {
  vi.clearAllMocks()
  api.gasPlanCapability.mockResolvedValue({
    planDate: '2026-10-20',
    gateStations: [
      { id: 1, stationCode: 'GS-01', stationName: '城东门站', dailyFlowM3: 100000, status: '运行' },
      { id: 2, stationCode: 'GS-02', stationName: '滨海门站', dailyFlowM3: 60000, status: '检修' }
    ],
    regulatorStations: [
      { id: 11, stationCode: 'RS-01', stationName: '兴业调压站', outletPressureMpa: 0.12,
        outletCapacityM3: 50000, status: '运行' },
      { id: 12, stationCode: 'RS-02', stationName: '花园调压箱', outletCapacityM3: null,
        status: '运行' }
    ]
  })
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('配额能力校验提示', () => {
  it('门站超进站能力与调压站超出口上限即时标红并拦截提交', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    const vm = wrapper.vm

    vm.form.planNo = 'GP-N1'
    vm.form.ownerName = '计划员甲'
    vm.form.dailyDemandM3 = 130000
    vm.form.demandSource = '调度预测'
    vm.form.quotas.push({ stationType: 'GATE', stationId: 1, stationCode: 'GS-01', quotaM3: 120000 })
    vm.form.quotas.push({ stationType: 'REGULATOR', stationId: 11, stationCode: 'RS-01', quotaM3: 60000 })
    await flushPromises()

    expect(wrapper.text()).toContain('超过能力上限 100000')
    expect(wrapper.text()).toContain('超过能力上限 50000')
    expect(wrapper.text()).toContain('门站配额合计 120000')
    expect(findButton(wrapper.element, '创建草稿')).toBeTruthy()
    findButton(wrapper.element, '创建草稿').click()
    await flushPromises()
    expect(api.createGasPlan).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('配额校验未通过')
  })

  it('负配额与缺需求来源拦截；调压站未核定能力不允许正配额', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    const vm = wrapper.vm
    vm.form.planNo = 'GP-N2'
    vm.form.ownerName = '计划员甲'
    vm.form.dailyDemandM3 = 1000
    // 缺需求来源
    vm.form.quotas.push({ stationType: 'GATE', stationId: 1, stationCode: 'GS-01', quotaM3: -5 })
    vm.form.quotas.push({ stationType: 'REGULATOR', stationId: 12, stationCode: 'RS-02', quotaM3: 10 })
    await flushPromises()

    expect(wrapper.text()).toContain('配额不能为负')
    expect(wrapper.text()).toContain('出口供出能力未核定')
    expect(wrapper.text()).toContain('缺少需求来源')
    findButton(wrapper.element, '创建草稿').click()
    await flushPromises()
    expect(api.createGasPlan).not.toHaveBeenCalled()
  })

  it('配额满足能力上限且需求来源齐全时创建成功并刷新', async () => {
    api.createGasPlan.mockResolvedValue({ id: 100 })
    const wrapper = mountEditor()
    await flushPromises()
    const vm = wrapper.vm
    vm.form.planNo = 'GP-N3'
    vm.form.ownerName = '计划员甲'
    vm.form.dailyDemandM3 = 130000
    vm.form.demandSource = '历史同期'
    vm.form.quotas.push({ stationType: 'GATE', stationId: 1, stationCode: 'GS-01', quotaM3: 90000 })
    vm.form.quotas.push({ stationType: 'REGULATOR', stationId: 11, stationCode: 'RS-01', quotaM3: 40000 })
    await flushPromises()

    expect(wrapper.text()).not.toContain('配额校验未通过')
    findButton(wrapper.element, '创建草稿').click()
    await flushPromises()

    expect(api.createGasPlan).toHaveBeenCalledTimes(1)
    const payload = api.createGasPlan.mock.calls[0][0]
    expect(payload).toMatchObject({
      planNo: 'GP-N3', planDate: '2026-10-20', dailyDemandM3: 130000,
      demandSource: '历史同期'
    })
    expect(payload.quotas).toEqual([
      { stationType: 'GATE', stationId: 1, quotaM3: 90000, remark: null },
      { stationType: 'REGULATOR', stationId: 11, quotaM3: 40000, remark: null }
    ])
  })

  it('未选站点/未填必填项时前端拦截且保留输入', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    const vm = wrapper.vm
    vm.form.quotas.push({ stationType: 'GATE', stationId: null, stationCode: '', quotaM3: 100 })
    await flushPromises()
    findButton(wrapper.element, '创建草稿').click()
    await flushPromises()
    expect(api.createGasPlan).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('计划编号不能为空')
    // 填入编号后仍因站点未选被拦
    vm.form.planNo = 'GP-N4'
    vm.form.planDate = '2026-10-20'
    vm.form.ownerName = '甲'
    vm.form.dailyDemandM3 = 1
    vm.form.demandSource = '调度预测'
    await flushPromises()
    findButton(wrapper.element, '创建草稿').click()
    await flushPromises()
    expect(wrapper.text()).toContain('未选择站点')
    expect(api.createGasPlan).not.toHaveBeenCalled()
  })

  it('同站重复行给重复提示', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    const vm = wrapper.vm
    vm.form.planNo = 'GP-N5'
    vm.form.ownerName = '甲'
    vm.form.dailyDemandM3 = 1
    vm.form.demandSource = '调度预测'
    vm.form.quotas.push({ stationType: 'GATE', stationId: 1, stationCode: 'GS-01', quotaM3: 10 })
    vm.form.quotas.push({ stationType: 'GATE', stationId: 1, stationCode: 'GS-01', quotaM3: 20 })
    await flushPromises()
    expect(wrapper.text()).toContain('重复，请合并为一条配额')
    findButton(wrapper.element, '创建草稿').click()
    await flushPromises()
    expect(api.createGasPlan).not.toHaveBeenCalled()
  })
})
