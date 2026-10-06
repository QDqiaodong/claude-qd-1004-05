import { reactive, ref } from 'vue'
import api from '../api'

/**
 * 供气计划列表的「日期 + 状态筛选 + 分页」状态机：
 *  - 改日期/状态一律回到第一页；翻页、改页大小保留已填条件；
 *  - 慢响应不得覆盖新请求：每次加载发序号，过期响应（含切租户前的在途请求）直接丢弃；
 *  - 查询失败不清空条件，原因写入 error 由页面提示。
 */
export function useGasPlanList() {
  const rows = ref([])
  const loading = ref(false)
  const total = ref(0)
  const error = ref('')
  const query = reactive({
    planDate: '',
    status: '',
    page: 1,
    size: 10
  })

  let ticket = 0

  function buildParams() {
    const date = (query.planDate || '').trim()
    return {
      planDate: date || undefined,
      status: query.status || undefined,
      page: query.page - 1,
      size: query.size
    }
  }

  async function load() {
    const mine = ++ticket
    loading.value = true
    error.value = ''
    try {
      const data = await api.listGasPlans(buildParams())
      if (mine !== ticket) return null
      rows.value = data.items || []
      total.value = data.total || 0
      return data
    } catch (e) {
      if (mine === ticket) error.value = (e && e.message) || '查询失败'
      return null
    } finally {
      if (mine === ticket) loading.value = false
    }
  }

  function search() {
    query.page = 1
    return load()
  }

  function changePage(page) {
    query.page = page
    return load()
  }

  function changeSize(size) {
    query.size = size
    query.page = 1
    return load()
  }

  function resetPage() {
    query.page = 1
    return load()
  }

  return { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage }
}
