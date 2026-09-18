<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  demandApi, resolveError,
  type ActivityDemand, type SiteVisit, type LockRecord, type DepositPreview
} from '../api'
import * as echarts from 'echarts'

const demands = ref<ActivityDemand[]>([])
const selectedDemandId = ref<number | null>(null)
const selectedDemand = ref<ActivityDemand | null>(null)
const recommendResults = ref<any[]>([])
const lockHistory = ref<LockRecord[]>([])
const chartRef = ref<HTMLDivElement>()

const visitDialogVisible = ref(false)
const visitSubmitting = ref(false)
const visitForm = reactive<SiteVisit>({
  venueId: 0,
  visitDate: '',
  timeSlot: 'MORNING',
  reservedPeople: 0
})
const visitVenueName = ref('')
const actionLoading = ref<number | null>(null)

const loadDemands = async () => {
  const res = await demandApi.getAll()
  // 0-待计算不出现在推荐页；6-活动已取消（已退押结算），不能再算推荐占场
  demands.value = res.data.filter((d: any) => d.matchStatus !== 0 && d.matchStatus !== 6)
}

const isStale = (demand: ActivityDemand | null) => {
  // 锁定已破裂（待重配）后，破裂前算出的旧名单不再是有效结果
  return !!demand && demand.matchStatus === 4
}

const loadRecommendResults = async (demandId: number) => {
  selectedDemandId.value = demandId
  selectedDemand.value = demands.value.find(d => d.id === demandId) || null
  recommendResults.value = []
  lockHistory.value = []

  if (selectedDemand.value) {
    try {
      const historyRes = await demandApi.getLockHistory(demandId)
      lockHistory.value = historyRes.data
    } catch {
      lockHistory.value = []
    }
  }

  if (isStale(selectedDemand.value)) {
    return
  }

  const res = await demandApi.getRecommendResults(demandId)
  recommendResults.value = res.data

  setTimeout(() => {
    initChart()
  }, 100)
}

const recalculate = async () => {
  if (!selectedDemandId.value) return
  try {
    const res = await demandApi.calculateRecommend(selectedDemandId.value)
    recommendResults.value = res.data.results || []
    const fresh = await demandApi.getById(selectedDemandId.value)
    selectedDemand.value = fresh.data
    lockHistory.value = []
    ElMessage.success('推荐结果已按最新条件重算')
    setTimeout(initChart, 100)
  } catch (error) {
    ElMessage.error(resolveError(error, '计算失败，请重试'))
  }
}

const confirmLock = async (venueId: number, venueName: string) => {
  if (!selectedDemandId.value) return
  try {
    await ElMessageBox.confirm(
      `确认看中场地「${venueName}」？确认后将按该场地日租金先冻结一笔押金，押金冻结成功才会进入待办活动并占用场地；` +
      `若客户押金余额不足，冻结会失败，当天不能开场、场地不予锁定。`,
      '口头看中场地 · 冻结押金',
      { type: 'warning', confirmButtonText: '冻结押金并锁定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = venueId
  try {
    await demandApi.confirmLock(selectedDemandId.value, venueId)
    ElMessage.success(`押金冻结成功，已锁定场地「${venueName}」，进入待办活动`)
    await refreshAfterLock()
  } catch (error) {
    // 冻结失败（如押金余额不足）：需求不进待办、场地未占用，错误信息点明原因
    ElMessage.error(resolveError(error, '押金冻结失败，当天不能开场'))
  } finally {
    actionLoading.value = null
  }
}

const cancelActivity = async () => {
  if (!selectedDemandId.value) return

  // 退档比例由后端按活动日距离分档计算，销售只能看、不能改
  let preview: DepositPreview
  try {
    const res = await demandApi.previewCancellation(selectedDemandId.value)
    preview = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '获取退押预估失败'))
    return
  }

  const tierText = preview.refundRate === 100
    ? '全额退回'
    : preview.refundRate === 50
      ? '只退一半'
      : '押金不退'
  const message =
    `取消时间距活动日 ${preview.daysToActivity} 天，按财务固定规则：${tierText}（${preview.ruleText}）。\n` +
    `冻结押金 ¥${preview.frozenAmount}，本次退回 ¥${preview.refundAmount}，没收 ¥${preview.forfeitAmount}。\n` +
    `退回比例由系统按日期自动核算，无法手工调整。确认取消该活动？`
  try {
    await ElMessageBox.confirm(message, '取消活动 · 分档退押', {
      type: 'warning',
      confirmButtonText: '确认取消活动',
      cancelButtonText: '再想想'
    })
  } catch {
    return
  }

  try {
    const res = await demandApi.cancelActivity(selectedDemandId.value)
    const tx = res.data
    ElMessage.success(`活动已取消：退回押金 ¥${tx.refundAmount}，没收 ¥${tx.forfeitAmount}`)
    selectedDemand.value = null
    recommendResults.value = []
    lockHistory.value = []
    await loadDemands()
  } catch (error) {
    ElMessage.error(resolveError(error, '取消活动失败'))
  }
}

const releaseLock = async () => {
  if (!selectedDemandId.value) return
  try {
    await ElMessageBox.confirm(
      '解除锁定用于回炉改条件重配（非客户取消活动）：将作废当前推荐名单、冻结押金全额退回，之后可修改人数、预算等并按新条件重算。确定解除？',
      '解除锁定 · 全额退押',
      { type: 'warning', confirmButtonText: '解除并全额退押', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await demandApi.releaseLock(selectedDemandId.value)
    ElMessage.success('已解除锁定，原推荐已作废')
    selectedDemand.value = null
    recommendResults.value = []
    lockHistory.value = []
    await loadDemands()
  } catch (error) {
    ElMessage.error(resolveError(error, '解除锁定失败'))
  }
}

const refreshAfterLock = async () => {
  if (!selectedDemandId.value) return
  const [demandRes, historyRes] = await Promise.all([
    demandApi.getById(selectedDemandId.value),
    demandApi.getLockHistory(selectedDemandId.value)
  ])
  selectedDemand.value = demandRes.data
  lockHistory.value = historyRes.data
}

const openVisitDialog = (result: any) => {
  if (!selectedDemand.value) return
  visitForm.venueId = result.venueId
  visitVenueName.value = result.venueName
  visitForm.visitDate = selectedDemand.value.expectedDate
    ? String(selectedDemand.value.expectedDate).slice(0, 10)
    : ''
  visitForm.timeSlot = 'MORNING'
  visitForm.reservedPeople = selectedDemand.value.expectedPeople
  visitDialogVisible.value = true
}

const submitVisit = async () => {
  if (!selectedDemandId.value) return
  if (!visitForm.visitDate) {
    ElMessage.warning('请选择试场日期')
    return
  }
  visitSubmitting.value = true
  try {
    await demandApi.registerSiteVisit(selectedDemandId.value, {
      ...visitForm,
      visitDate: String(visitForm.visitDate).slice(0, 10)
    })
    ElMessage.success(
      `踩点试场登记成功：${visitVenueName.value} ${visitForm.visitDate} ${visitForm.timeSlot === 'MORNING' ? '上午' : '下午'}`
    )
    visitDialogVisible.value = false
  } catch (error) {
    ElMessage.error(resolveError(error, '试场登记失败'))
  } finally {
    visitSubmitting.value = false
  }
}

const initChart = () => {
  if (!chartRef.value || recommendResults.value.length === 0) return

  const chart = echarts.init(chartRef.value)

  const xAxisData = recommendResults.value.map(r => r.venueName)
  const seriesData = [
    { name: '匹配度', data: recommendResults.value.map(r => r.matchScore) },
    { name: '人数', data: recommendResults.value.map(r => r.capacityScore) },
    { name: '设施', data: recommendResults.value.map(r => r.facilityScore) },
    { name: '类型', data: recommendResults.value.map(r => r.activityTypeScore) },
    { name: '预算', data: recommendResults.value.map(r => r.budgetScore) }
  ]

  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['匹配度', '人数', '设施', '类型', '预算'] },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: xAxisData },
    yAxis: { type: 'value', max: 100 },
    series: seriesData.map(d => ({
      name: d.name,
      type: 'bar' as const,
      data: d.data
    }))
  })
}

const getScoreColor = (score: number) => {
  if (score >= 80) return '#67c23a'
  if (score >= 60) return '#e6a23c'
  return '#f56c6c'
}

const lockStatusMeta = (record: LockRecord) => {
  if (record.status === 'LOCKED') return { text: '锁定中', type: 'success' as const }
  if (record.status === 'BROKEN') return { text: '已破裂', type: 'danger' as const }
  if (record.status === 'CANCELED') return { text: '已取消', type: 'danger' as const }
  return { text: '已解除', type: 'info' as const }
}

onMounted(loadDemands)
</script>

<template>
  <div class="recommend-container">
    <h3>推荐结果分析</h3>

    <div v-if="demands.length === 0" class="empty-state">
      <el-alert type="info" title="暂无推荐记录" description="请先提交需求并计算推荐" show-icon />
    </div>

    <div v-else>
      <div class="demand-select">
        <span>选择需求：</span>
        <el-select v-model="selectedDemandId" placeholder="请选择需求" style="width: 320px" @change="loadRecommendResults">
          <el-option v-for="demand in demands" :key="demand.id" :label="demand.demandName" :value="demand.id!">
            <span>{{ demand.demandName }}</span>
            <el-tag
              v-if="demand.locked === 1"
              :type="demand.opened === 1 ? 'danger' : 'success'"
              size="small"
              style="margin-left: 8px"
            >{{ demand.opened === 1 ? '已开场' : '已锁定' }} {{ demand.lockedVenueName }}</el-tag>
            <el-tag v-else-if="demand.matchStatus === 4" type="warning" size="small" style="margin-left: 8px">待重配</el-tag>
          </el-option>
        </el-select>
      </div>

      <template v-if="selectedDemand">
        <el-alert
          v-if="selectedDemand.locked === 1"
          type="success"
          show-icon
          :closable="false"
          class="state-alert"
        >
          <template #title>
            <div class="locked-title">
              <span>
                该需求已冻结押金 ¥{{ selectedDemand.depositAmount ?? 0 }} 并锁定场地「{{ selectedDemand.lockedVenueName }}」，进入待办活动；
                期望日期、人数、预算上限、必备设施已冻结。
                <el-tag v-if="selectedDemand.opened === 1" type="danger" size="small" style="margin-left: 6px">
                  当天两岗已签到并开场
                </el-tag>
                <el-tag
                  v-if="selectedDemand.currentInvoiceStatus === 'VALID'"
                  type="success"
                  size="small"
                  effect="dark"
                  style="margin-left: 6px"
                >已开有效发票 {{ selectedDemand.currentInvoiceNo }} ¥{{ selectedDemand.currentInvoiceAmount }}</el-tag>
              </span>
              <span class="locked-actions">
                <el-button type="danger" size="small" plain @click="cancelActivity">
                  取消活动（按日退押）
                </el-button>
                <el-button type="warning" size="small" plain @click="releaseLock">
                  解除锁定并作废推荐（全额退押）
                </el-button>
              </span>
            </div>
          </template>
        </el-alert>

        <el-alert
          v-else-if="isStale(selectedDemand)"
          type="error"
          show-icon
          :closable="false"
          class="state-alert"
          :title="`锁定已破裂，需求回到待重配。破裂原因：${selectedDemand.lockBreakReason || '未知'}`"
          description="破裂前的旧推荐名单已作废，不再作为有效结果展示，请按当前条件重新计算推荐。"
        >
          <template #default>
            <div class="break-actions">
              <span>破裂前的旧推荐名单已作废，不再作为有效结果展示，请按当前条件重新计算推荐。</span>
              <el-button type="primary" size="small" @click="recalculate">按新条件重新计算</el-button>
            </div>
          </template>
        </el-alert>

        <div v-if="lockHistory.length > 0" class="lock-history">
          <h4>锁定历史</h4>
          <el-table :data="lockHistory" border size="small">
            <el-table-column prop="venueName" label="场地" min-width="120" />
            <el-table-column label="状态" width="90">
              <template #default="scope">
                <el-tag :type="lockStatusMeta(scope.row).type" size="small">{{ lockStatusMeta(scope.row).text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="breakReason" label="破裂/解除原因" min-width="260" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.breakReason || '-' }}</template>
            </el-table-column>
            <el-table-column prop="lockedAt" label="锁定时间" width="170" />
            <el-table-column prop="releasedAt" label="结束时间" width="170">
              <template #default="scope">{{ scope.row.releasedAt || '-' }}</template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <div v-if="selectedDemand && !isStale(selectedDemand) && selectedDemand.locked !== 1 && recommendResults.length === 0" class="no-result">
        <el-alert type="warning" title="无匹配场地" description="该需求暂未找到合适的场地" show-icon />
      </div>

      <div v-if="selectedDemand && !isStale(selectedDemand) && recommendResults.length > 0">
        <div class="chart-section">
          <h4>匹配度可视化分析</h4>
          <div ref="chartRef" class="chart"></div>
        </div>

        <div class="result-table-section">
          <h4>推荐详情</h4>
          <el-table :data="recommendResults" border>
            <el-table-column prop="recommendOrder" label="排名" width="60" />
            <el-table-column prop="venueName" label="场地名称" min-width="120" />
            <el-table-column prop="matchScore" label="匹配度" width="100">
              <template #default="scope">
                <span :style="{ color: getScoreColor(scope.row.matchScore), fontWeight: 'bold' }">
                  {{ scope.row.matchScore }}分
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="capacityScore" label="人数匹配" width="100">
              <template #default="scope">
                <el-progress :percentage="Math.round(scope.row.capacityScore)" :color="getScoreColor(scope.row.capacityScore)" />
              </template>
            </el-table-column>
            <el-table-column prop="facilityScore" label="设施匹配" width="100">
              <template #default="scope">
                <el-progress :percentage="Math.round(scope.row.facilityScore)" :color="getScoreColor(scope.row.facilityScore)" />
              </template>
            </el-table-column>
            <el-table-column prop="activityTypeScore" label="类型匹配" width="100">
              <template #default="scope">
                <el-progress :percentage="Math.round(scope.row.activityTypeScore)" :color="getScoreColor(scope.row.activityTypeScore)" />
              </template>
            </el-table-column>
            <el-table-column prop="budgetScore" label="预算匹配" width="100">
              <template #default="scope">
                <el-progress :percentage="Math.round(scope.row.budgetScore)" :color="getScoreColor(scope.row.budgetScore)" />
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="匹配说明" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="selectedDemand.locked !== 1" label="操作" width="230" fixed="right">
              <template #default="scope">
                <el-button
                  type="primary"
                  size="small"
                  :loading="actionLoading === scope.row.venueId"
                  @click="confirmLock(scope.row.venueId, scope.row.venueName)"
                >口头看中 · 冻结押金</el-button>
                <el-button size="small" @click="openVisitDialog(scope.row)">踩点试场</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <el-dialog v-model="visitDialogVisible" title="登记踩点试场" width="480px">
      <el-form label-width="100px">
        <el-form-item label="试场场地">
          <el-input :model-value="visitVenueName" disabled />
        </el-form-item>
        <el-form-item label="试场日期" required>
          <el-date-picker v-model="visitForm.visitDate" type="date" placeholder="请选择试场日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="试场时段" required>
          <el-radio-group v-model="visitForm.timeSlot">
            <el-radio value="MORNING">上午</el-radio>
            <el-radio value="AFTERNOON">下午</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="预留人数">
          <el-input-number v-model="visitForm.reservedPeople" :min="1" :max="1000" />
          <span class="hint">该人数会从场地当天可排容量中预留</span>
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="每条需求只能登记一档试场；若该场地当天已有正式活动锁定，登记会失败并提示被哪条需求挡住。"
        />
      </el-form>
      <template #footer>
        <el-button @click="visitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="visitSubmitting" @click="submitVisit">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.recommend-container {
  max-width: 100%;
}

.recommend-container h3 {
  font-size: 18px;
  margin-bottom: 16px;
}

.empty-state {
  margin-top: 40px;
}

.demand-select {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.demand-select span {
  font-weight: 500;
}

.state-alert {
  margin-bottom: 16px;
}

.locked-title {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.locked-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.break-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
}

.lock-history {
  margin: 16px 0;
}

.lock-history h4 {
  font-size: 15px;
  margin-bottom: 10px;
}

.no-result {
  margin-top: 20px;
}

.chart-section {
  margin-bottom: 24px;
}

.chart-section h4 {
  font-size: 16px;
  margin-bottom: 12px;
}

.chart {
  height: 300px;
  background: #fafafa;
  border-radius: 8px;
  padding: 10px;
}

.result-table-section h4 {
  font-size: 16px;
  margin-bottom: 12px;
}

.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
