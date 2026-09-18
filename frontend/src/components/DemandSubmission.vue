<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  demandApi, resolveError,
  type ActivityDemand, type SiteVisit, type DepositPreview
} from '../api'

const activityCategories = ['会议培训', '产品发布', '团建活动', '学术研讨', '文艺演出', '展览展示', '婚礼庆典', '生日派对']
const facilityOptions = ['投影仪', '音响系统', '麦克风', '白板', 'WiFi', '空调', '餐饮服务', '停车位', '舞台灯光', '直播设备']

const demandForm = reactive<ActivityDemand>({
  customerName: '',
  customerPhone: '',
  demandName: '',
  expectedDate: '',
  expectedPeople: 0,
  activityCategory: '',
  budgetMin: 0,
  budgetMax: 0,
  requiredFacilities: '',
  specialRequirements: '',
  matchStatus: 0,
  locked: 0
})

const selectedFacilities = ref<string[]>([])
const isSubmitting = ref(false)
const showRecommend = ref(false)
const recommendResults = ref<any[]>([])
const warningMessage = ref('')
const missingResources = ref<string[]>([])
const matchStatus = ref(0)
const currentDemandId = ref<number | null>(null)
const lockedVenueId = ref<number | null>(null)
const lockedVenueName = ref('')
const frozenDeposit = ref<number | null>(null)
const actionLoading = ref<number | null>(null)
const cancelLoading = ref(false)

const visitDialogVisible = ref(false)
const visitSubmitting = ref(false)
const visitForm = reactive<SiteVisit>({
  venueId: 0,
  visitDate: '',
  timeSlot: 'MORNING',
  reservedPeople: 0
})
const visitVenueName = ref('')

const handleFacilityChange = (val: string[]) => {
  selectedFacilities.value = val
  demandForm.requiredFacilities = val.join(',')
}

const resetRecommendState = (demand: ActivityDemand) => {
  currentDemandId.value = demand.id ?? null
  lockedVenueId.value = demand.lockedVenueId ?? null
  lockedVenueName.value = demand.lockedVenueName ?? ''
  frozenDeposit.value = demand.depositAmount ?? null
}

const applyRecommendResponse = (data: any) => {
  recommendResults.value = data.results
  warningMessage.value = data.warningMessage || ''
  missingResources.value = data.missingResources || []
  matchStatus.value = data.matchStatus || 0
  showRecommend.value = true
}

const submitDemand = async () => {
  if (!demandForm.customerName || !demandForm.demandName || !demandForm.expectedPeople || !demandForm.activityCategory) {
    ElMessage.warning('请填写必填项')
    return
  }

  isSubmitting.value = true
  try {
    const res = await demandApi.create(demandForm)
    resetRecommendState(res.data)
    ElMessage.success('需求提交成功')

    const recommendRes = await demandApi.calculateRecommend(res.data.id!)
    applyRecommendResponse(recommendRes.data)

    if (recommendResults.value.length === 0) {
      ElMessage.warning('暂无匹配场地，已生成需求预警')
    } else if (warningMessage.value) {
      ElMessage.warning(warningMessage.value)
    }
  } catch (error) {
    ElMessage.error(resolveError(error, '提交失败，请重试'))
  } finally {
    isSubmitting.value = false
  }
}

const recalculate = async () => {
  if (!currentDemandId.value) return

  isSubmitting.value = true
  try {
    const res = await demandApi.calculateRecommend(currentDemandId.value)
    applyRecommendResponse(res.data)
    lockedVenueId.value = null
    lockedVenueName.value = ''
    frozenDeposit.value = null
    ElMessage.success('推荐结果已刷新')

    if (recommendResults.value.length === 0) {
      ElMessage.warning('暂无匹配场地，已生成需求预警')
    } else if (warningMessage.value) {
      ElMessage.warning(warningMessage.value)
    }
  } catch (error) {
    ElMessage.error(resolveError(error, '计算失败，请重试'))
  } finally {
    isSubmitting.value = false
  }
}

const confirmLock = async (venueId: number, venueName: string) => {
  if (!currentDemandId.value) return
  try {
    await ElMessageBox.confirm(
      `口头看中场地「${venueName}」？确认后将按该场地日租金先冻结一笔押金，押金冻结成功才会进入待办活动并占用场地；` +
      `若客户押金余额不足，冻结会失败，当天不能开场、场地不予锁定。`,
      '口头看中场地 · 冻结押金',
      { type: 'warning', confirmButtonText: '冻结押金并锁定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = venueId
  try {
    await demandApi.confirmLock(currentDemandId.value, venueId)
    const freshRes = await demandApi.getById(currentDemandId.value)
    resetRecommendState(freshRes.data)
    matchStatus.value = 5
    ElMessage.success(`押金冻结成功，已锁定场地「${venueName}」，进入待办活动`)
  } catch (error) {
    // 冻结失败（如押金余额不足）：需求不进待办、场地未占用
    ElMessage.error(resolveError(error, '押金冻结失败，当天不能开场'))
  } finally {
    actionLoading.value = null
  }
}

const releaseLock = async () => {
  if (!currentDemandId.value) return
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
    await demandApi.releaseLock(currentDemandId.value)
    lockedVenueId.value = null
    lockedVenueName.value = ''
    frozenDeposit.value = null
    recommendResults.value = []
    matchStatus.value = 4
    ElMessage.success('已解除锁定，押金全额退回，原推荐已作废，请按新条件重新计算')
  } catch (error) {
    ElMessage.error(resolveError(error, '解除锁定失败'))
  }
}

const cancelActivity = async () => {
  if (!currentDemandId.value) return

  // 退档比例由后端按活动日距离分档计算，销售只能看、不能改
  let preview: DepositPreview
  try {
    const res = await demandApi.previewCancellation(currentDemandId.value)
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
  try {
    await ElMessageBox.confirm(
      `取消时间距活动日 ${preview.daysToActivity} 天，按财务固定规则：${tierText}（${preview.ruleText}）。\n` +
      `冻结押金 ¥${preview.frozenAmount}，本次退回 ¥${preview.refundAmount}，没收 ¥${preview.forfeitAmount}。\n` +
      `退回比例由系统按日期自动核算，无法手工调整。确认取消该活动？`,
      '取消活动 · 分档退押',
      { type: 'warning', confirmButtonText: '确认取消活动', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }

  cancelLoading.value = true
  try {
    const res = await demandApi.cancelActivity(currentDemandId.value)
    const tx = res.data
    lockedVenueId.value = null
    lockedVenueName.value = ''
    frozenDeposit.value = null
    matchStatus.value = 6
    showRecommend.value = false
    ElMessage.success(`活动已取消：退回押金 ¥${tx.refundAmount}，没收 ¥${tx.forfeitAmount}`)
  } catch (error) {
    ElMessage.error(resolveError(error, '取消活动失败'))
  } finally {
    cancelLoading.value = false
  }
}

const openVisitDialog = (result: any) => {
  visitForm.venueId = result.venueId
  visitVenueName.value = result.venueName
  visitForm.visitDate = demandForm.expectedDate
    ? String(demandForm.expectedDate).slice(0, 10)
    : ''
  visitForm.timeSlot = 'MORNING'
  visitForm.reservedPeople = demandForm.expectedPeople
  visitDialogVisible.value = true
}

const submitVisit = async () => {
  if (!currentDemandId.value) return
  if (!visitForm.visitDate) {
    ElMessage.warning('请选择试场日期')
    return
  }
  visitSubmitting.value = true
  try {
    await demandApi.registerSiteVisit(currentDemandId.value, {
      ...visitForm,
      visitDate: String(visitForm.visitDate).slice(0, 10)
    })
    ElMessage.success(`踩点试场登记成功：${visitVenueName.value} ${visitForm.visitDate} ${visitForm.timeSlot === 'MORNING' ? '上午' : '下午'}`)
    visitDialogVisible.value = false
  } catch (error) {
    ElMessage.error(resolveError(error, '试场登记失败'))
  } finally {
    visitSubmitting.value = false
  }
}

const getScoreColor = (score: number) => {
  if (score >= 80) return 'green'
  if (score >= 60) return 'orange'
  return 'red'
}

const getStatusText = (status: number) => {
  switch (status) {
    case 1: return { text: '完全匹配', type: 'success' }
    case 2: return { text: '无匹配', type: 'danger' }
    case 3: return { text: '部分匹配', type: 'warning' }
    case 4: return { text: '待重配', type: 'warning' }
    case 5: return { text: '已锁定（待办活动）', type: 'success' }
    case 6: return { text: '已取消（已退押）', type: 'info' }
    default: return { text: '待匹配', type: 'info' }
  }
}
</script>

<template>
  <div class="demand-container">
    <el-form :model="demandForm" label-width="120px" class="demand-form">
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="客户姓名" required>
            <el-input v-model="demandForm.customerName" placeholder="请输入客户姓名" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="联系电话">
            <el-input v-model="demandForm.customerPhone" placeholder="请输入联系电话" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="需求名称" required>
            <el-input v-model="demandForm.demandName" placeholder="请输入需求名称" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="期望日期">
            <el-date-picker v-model="demandForm.expectedDate" type="date" placeholder="请选择日期" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="20">
        <el-col :span="8">
          <el-form-item label="预计人数" required>
            <el-input-number v-model="demandForm.expectedPeople" :min="1" :max="1000" placeholder="请输入人数" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="活动类型" required>
            <el-select v-model="demandForm.activityCategory" placeholder="请选择活动类型">
              <el-option v-for="cat in activityCategories" :key="cat" :label="cat" :value="cat" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="预算范围">
            <el-input-number v-model="demandForm.budgetMin" :min="0" placeholder="最低预算" />
            <span style="margin: 0 10px;">-</span>
            <el-input-number v-model="demandForm.budgetMax" :min="0" placeholder="最高预算" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="必备配套设施">
        <el-select v-model="selectedFacilities" multiple placeholder="请选择配套设施" @change="handleFacilityChange">
          <el-option v-for="facility in facilityOptions" :key="facility" :label="facility" :value="facility" />
        </el-select>
      </el-form-item>

      <el-form-item label="特殊要求">
        <el-input v-model="demandForm.specialRequirements" type="textarea" :rows="3" placeholder="请输入特殊要求（可选）" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="isSubmitting" @click="submitDemand">提交需求并计算推荐</el-button>
        <el-button v-if="showRecommend && !lockedVenueId && matchStatus !== 6" :loading="isSubmitting" @click="recalculate">重新计算推荐</el-button>
        <template v-if="lockedVenueId">
          <el-button type="danger" plain :loading="cancelLoading" @click="cancelActivity">
            取消活动（按日退押）
          </el-button>
          <el-button type="warning" plain @click="releaseLock">
            解除锁定「{{ lockedVenueName }}」（全额退押、作废推荐）
          </el-button>
        </template>
      </el-form-item>

      <el-alert
        v-if="lockedVenueId"
        type="success"
        show-icon
        :closable="false"
        class="lock-tip"
        :title="`已冻结押金 ¥${frozenDeposit ?? 0}，锁定场地「${lockedVenueName}」并进入待办活动；期望日期、人数、预算上限、必备设施已冻结。客户取消活动按距活动日远近退押：≥3天全退、三天内半退、当天不退。`"
      />

      <el-alert
        v-if="matchStatus === 6"
        type="info"
        show-icon
        :closable="false"
        class="lock-tip"
        title="该活动已取消并完成退押结算，如需继续办活动请重新提交需求。"
      />
    </el-form>

    <div v-if="showRecommend" class="recommend-section">
      <div class="section-header">
        <h3>推荐结果</h3>
        <el-tag :type="getStatusText(matchStatus).type" size="small">{{ getStatusText(matchStatus).text }}</el-tag>
      </div>

      <div v-if="warningMessage || missingResources.length > 0" class="warning-section">
        <el-alert
          :type="recommendResults.length === 0 ? 'warning' : 'info'"
          :title="recommendResults.length === 0 ? '无匹配场地预警' : '资源缺口提示'"
          :description="warningMessage"
          show-icon
          :closable="false"
        />
        <div v-if="missingResources.length > 0" class="missing-resources">
          <h4>缺失资源明细：</h4>
          <ul>
            <li v-for="(resource, index) in missingResources" :key="index">
              <el-icon><CircleClose /></el-icon>
              <span>{{ resource }}</span>
            </li>
          </ul>
        </div>
      </div>

      <div v-if="recommendResults.length === 0 && !warningMessage" class="no-result">
        <el-alert type="warning" title="无匹配场地预警" description="当前需求暂未找到合适的场地，请考虑调整需求参数" show-icon />
      </div>

      <div v-else-if="recommendResults.length > 0" class="result-list">
        <div v-for="result in recommendResults" :key="result.id" class="result-card">
          <div class="result-header">
            <span class="rank">第{{ result.recommendOrder }}名</span>
            <h4>{{ result.venueName }}</h4>
            <span :class="['score', getScoreColor(result.matchScore)]">{{ result.matchScore }}分</span>
          </div>
          <div class="score-details">
            <div class="score-item">
              <span>容纳人数</span>
              <el-progress :percentage="Math.round(result.capacityScore)" :color="getScoreColor(result.capacityScore)" />
            </div>
            <div class="score-item">
              <span>配套设施</span>
              <el-progress :percentage="Math.round(result.facilityScore)" :color="getScoreColor(result.facilityScore)" />
            </div>
            <div class="score-item">
              <span>活动类型</span>
              <el-progress :percentage="Math.round(result.activityTypeScore)" :color="getScoreColor(result.activityTypeScore)" />
            </div>
            <div class="score-item">
              <span>预算匹配</span>
              <el-progress :percentage="Math.round(result.budgetScore)" :color="getScoreColor(result.budgetScore)" />
            </div>
          </div>
          <div class="reason">
            <span>匹配说明：</span>{{ result.reason }}
          </div>
          <div class="card-actions">
            <el-tag v-if="lockedVenueId === result.venueId" type="success">已锁定该场地</el-tag>
            <template v-else-if="!lockedVenueId">
              <el-button
                type="primary"
                size="small"
                :loading="actionLoading === result.venueId"
                @click="confirmLock(result.venueId, result.venueName)"
              >口头看中 · 冻结押金</el-button>
              <el-button size="small" @click="openVisitDialog(result)">登记踩点试场</el-button>
            </template>
            <el-tag v-else type="info" size="small">已锁定其他场地</el-tag>
          </div>
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
.demand-container {
  max-width: 900px;
}

.demand-form {
  background: #fafafa;
  padding: 24px;
  border-radius: 8px;
}

.lock-tip {
  margin-top: 8px;
}

.recommend-section {
  margin-top: 24px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.section-header h3 {
  font-size: 18px;
  margin: 0;
}

.warning-section {
  margin-bottom: 16px;
}

.missing-resources {
  margin-top: 12px;
  padding: 12px;
  background: #fef0f0;
  border-radius: 8px;
}

.missing-resources h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #f56c6c;
}

.missing-resources ul {
  list-style: none;
  padding: 0;
  margin: 0;
}

.missing-resources li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
  padding: 4px 0;
}

.no-result {
  background: #fff7e6;
  border-radius: 8px;
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.result-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.rank {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
  padding: 4px 12px;
  border-radius: 4px;
  font-weight: 600;
}

.result-header h4 {
  flex: 1;
  margin: 0;
  font-size: 16px;
}

.score {
  font-size: 24px;
  font-weight: bold;
}

.score.green {
  color: #67c23a;
}

.score.orange {
  color: #e6a23c;
}

.score.red {
  color: #f56c6c;
}

.score-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.score-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.score-item span {
  width: 80px;
  font-size: 14px;
}

.reason {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
  font-size: 14px;
  color: #606266;
}

.card-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
