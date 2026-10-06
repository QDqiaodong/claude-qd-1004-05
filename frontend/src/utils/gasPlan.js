// 门站日输气计划与供气配额编排：状态标签、配额前端校验、版本差异类型文案。
// 后端在提交复核时会做权威能力校验（门站进站能力 / 调压站出口上限 / 负配额 / 需求来源），
// 前端校验只用于即时提示与减少无效提交，绝不绕过后端拦截。
export const GP_STATUS = ['草稿', '待复核', '已提交', '已冻结']

export function gpStatusTagType(status) {
  switch (status) {
    case '草稿':
      return 'info'
    case '待复核':
      return 'warning'
    case '已提交':
      return 'primary'
    case '已冻结':
      return 'success'
    default:
      return 'info'
  }
}

export function stationTypeLabel(type) {
  return type === 'GATE' ? '门站' : type === 'REGULATOR' ? '调压站' : type
}

export function diffChangeTypeLabel(t) {
  return {
    ADDED: '新增',
    REMOVED: '删除',
    CHANGED: '配额变化',
    SAME: '无变化'
  }[t] || t
}

function num(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 对一条配额做前端校验，返回问题数组（空数组表示通过）。
 * capacity 为当前档案能力（m³/日）；null 表示调压站出口能力未核定。
 */
export function validateQuota(row, capacity) {
  const problems = []
  const quota = num(row.quotaM3)
  if (quota === null) {
    problems.push('配额不能为空')
    return problems
  }
  if (quota < 0) {
    problems.push('配额不能为负')
  }
  if (capacity === null || capacity === undefined) {
    if (quota > 0) problems.push('出口供出能力未核定，不能编排大于 0 的配额')
  } else if (quota > Number(capacity)) {
    problems.push(`配额 ${quota} 超过能力上限 ${capacity} m³`)
  }
  return problems
}

/** 汇总整张草稿的校验结果：{ blocks: string[], gateQuotaTotal, gateCapacityTotal, ... } */
export function summarizeValidation(form, capability) {
  const blocks = []
  const warns = []
  let gateQuotaTotal = 0
  let gateCapacityTotal = 0
  let regQuotaTotal = 0
  let regCapacityTotal = 0
  const seen = new Set()

  const capOf = (q) => {
    if (q.stationType === 'GATE') {
      return capability.gateStations.find(s => s.id === q.stationId)?.dailyFlowM3
    }
    return capability.regulatorStations.find(s => s.id === q.stationId)?.outletCapacityM3
  }

  for (const q of form.quotas) {
    const key = `${q.stationType}#${q.stationId}`
    if (seen.has(key)) blocks.push(`站点 ${q.stationCode || q.stationId} 重复，请合并为一条配额`)
    seen.add(key)
    const cap = capOf(q)
    if (cap === undefined) {
      blocks.push(`站点 ${q.stationId} 不在当前片区档案中`)
      continue
    }
    const problems = validateQuota(q, cap)
    for (const p of problems) blocks.push(`${q.stationCode || ''} ${p}`)
    const quota = num(q.quotaM3) || 0
    if (q.stationType === 'GATE') {
      gateQuotaTotal += quota
      gateCapacityTotal += Number(cap) || 0
    } else {
      regQuotaTotal += quota
      regCapacityTotal += Number(cap) || 0
    }
  }

  if (!form.demandSource || !form.demandSource.trim()) blocks.push('缺少需求来源，请填写调度预测/历史同期/大用户申报')
  const demand = num(form.dailyDemandM3)
  if (demand === null || demand < 0) blocks.push('片区日需求量不能为空且不能为负')
  if (gateQuotaTotal > gateCapacityTotal) {
    blocks.push(`门站配额合计 ${gateQuotaTotal} m³ 超过进站能力合计 ${gateCapacityTotal} m³`)
  }
  if (demand !== null && demand > 0 && gateQuotaTotal + regQuotaTotal < demand) {
    warns.push(`配额合计 ${gateQuotaTotal + regQuotaTotal} m³ 未覆盖片区需求 ${demand} m³`)
  }
  return { blocks, warns, gateQuotaTotal, gateCapacityTotal, regQuotaTotal, regCapacityTotal }
}
