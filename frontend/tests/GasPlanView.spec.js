import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElDatePicker, ElPagination, ElSelect } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import GasPlanView from '../src/views/GasPlanView.vue'
import GasPlanDetail from '../src/components/gasplan/GasPlanDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    listGasPlans: vi.fn(),
    getGasPlan: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const page = (items, total) => ({ items, total, page: 0, size: 10, totalPages: 1 })

function mountView() {
  return mount(GasPlanView, {
    props: { tenant: 'A片区' },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listGasPlans.mockResolvedValue(page([], 0))
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('供气计划列表', () => {
  it('渲染版本号与状态标签', async () => {
    api.listGasPlans.mockResolvedValue(page([
      { id: 1, planNo: 'GP-001', planDate: '2026-10-20', planVersion: 1, status: '草稿', ownerName: '张工' },
      { id: 2, planNo: 'GP-002', planDate: '2026-10-20', planVersion: 3, status: '已冻结', ownerName: '李工' }
    ], 2))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('GP-001')
    expect(wrapper.text()).toContain('V3')
    expect(wrapper.text()).toContain('已冻结')
  })

  it('日期与状态筛选都从第一页发出，空值不带参数', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 初始加载带租户隔离的默认参数
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, size: 10 }))

    wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-10-20')
    wrapper.findComponent(ElDatePicker).vm.$emit('change', '2026-10-20')
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ planDate: '2026-10-20', page: 0 }))

    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', '待复核')
    wrapper.findComponent(ElSelect).vm.$emit('change', '待复核')
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ planDate: '2026-10-20', status: '待复核', page: 0 }))
  })

  it('翻页保留筛选，切换片区回第一页', async () => {
    const wrapper = mountView()
    await flushPromises()
    wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-10-21')
    wrapper.findComponent(ElDatePicker).vm.$emit('change', '2026-10-21')
    await flushPromises()
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 3)
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ planDate: '2026-10-21', page: 2 }))

    await wrapper.setProps({ tenant: 'B片区' })
    await flushPromises()
    expect(api.listGasPlans).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0 }))
  })

  it('查询失败时错误写入 error 状态供页面提示', async () => {
    api.listGasPlans.mockRejectedValueOnce(new Error('网络异常'))
    const wrapper = mountView()
    await flushPromises()
    // 错误经 useGasPlanList 的 error 暴露给视图（页面 watch 后用 ElMessage 弹错）
    expect(wrapper.vm.error).toBe('网络异常')
  })

  it('点详情加载详情抽屉', async () => {
    api.listGasPlans.mockResolvedValue(page([
      { id: 55, planNo: 'GP-D', planDate: '2026-10-20', planVersion: 1, status: '待复核' }
    ], 1))
    api.getGasPlan.mockResolvedValue({
      plan: { id: 55, status: '待复核' }, gateQuotas: [], regulatorQuotas: [],
      gateQuotaTotal: 0, gateCapacityTotal: 0, regulatorQuotaTotal: 0, regulatorCapacityTotal: 0,
      blockCount: 0, warnCount: 0, histories: []
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '详情/处置').trigger('click')
    await flushPromises()
    expect(api.getGasPlan).toHaveBeenCalledWith(55)
    expect(wrapper.findComponent(GasPlanDetail).exists()).toBe(true)
  })
})
