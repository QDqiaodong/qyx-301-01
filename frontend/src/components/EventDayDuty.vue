<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dutyApi, resolveError, type DayDutyView, type DutyPost } from '../api'

const todayStr = () => {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const selectedDate = ref<string>(todayStr())
const dayList = ref<DayDutyView[]>([])
const loading = ref(false)
const openingDemandId = ref<number | null>(null)
const withdrawingKey = ref<string | null>(null)

const signDialogVisible = ref(false)
const signSubmitting = ref(false)
const signForm = ref({
  demandId: 0,
  venueName: '',
  post: 'PRIMARY' as DutyPost,
  postLabel: '主值守',
  staffName: ''
})

const loadDay = async () => {
  loading.value = true
  try {
    // 当天页数据全部来自后端持久化的签到行与开场条，再进来看到的还是同一份
    const res = await dutyApi.getDayView(selectedDate.value || undefined)
    dayList.value = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载当天值守信息失败'))
  } finally {
    loading.value = false
  }
}

const statusMeta = (row: DayDutyView) => {
  if (row.opened) return { text: '已开场', type: 'success' as const }
  if (row.canOpen) return { text: '可开场（两岗已齐）', type: 'warning' as const }
  return { text: '已锁定 · 待两岗签到', type: 'info' as const }
}

const postPanels = (row: DayDutyView) => [
  { key: 'PRIMARY' as DutyPost, label: '主值守', view: row.primaryPost },
  { key: 'FLEX' as DutyPost, label: '机动', view: row.flexPost }
]

const formatTime = (value?: string | null) => (value ? value.slice(5, 16) : '-')

const openSignDialog = (row: DayDutyView, post: DutyPost, postLabel: string) => {
  signForm.value = { demandId: row.demandId, venueName: row.venueName, post, postLabel, staffName: '' }
  signDialogVisible.value = true
}

const submitSign = async () => {
  const name = signForm.value.staffName.trim()
  if (!name) {
    ElMessage.warning('请填写签到人姓名')
    return
  }
  signSubmitting.value = true
  try {
    await dutyApi.signIn(signForm.value.demandId, signForm.value.post, name)
    ElMessage.success(`${signForm.value.postLabel}「${name}」签到成功`)
    signDialogVisible.value = false
    await loadDay()
  } catch (error) {
    // 该岗已被占用（含两人前后脚抢同一岗）：后端 409 会带占用人信息
    ElMessage.error(resolveError(error, '签到失败'))
    await loadDay()
  } finally {
    signSubmitting.value = false
  }
}

const withdraw = async (row: DayDutyView, post: DutyPost, postLabel: string, staffName: string) => {
  const reopenTip = row.opened
    ? '场地当前已开场：撤岗后两岗不齐，将退回已锁定、当天开场条作废（押金冻结不变）。'
    : '撤岗后该岗位空出，需重新签到。'
  try {
    await ElMessageBox.confirm(
      `确认${postLabel}「${staffName}」撤岗？${reopenTip}`,
      '中途撤岗',
      { type: 'warning', confirmButtonText: '确认撤岗', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  withdrawingKey.value = `${row.demandId}-${post}`
  try {
    await dutyApi.withdraw(row.demandId, post)
    ElMessage.success(
      row.opened
        ? `${postLabel}已撤岗，场地退回已锁定、当天开场条作废（押金冻结不变）`
        : `${postLabel}已撤岗`
    )
    await loadDay()
  } catch (error) {
    ElMessage.error(resolveError(error, '撤岗失败'))
    await loadDay()
  } finally {
    withdrawingKey.value = null
  }
}

const openVenue = async (row: DayDutyView) => {
  if (!row.canOpen) {
    ElMessage.warning('按安保规定，主值守、机动两岗都签到齐全才能开场')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认开场「${row.venueName}」？主值守「${row.primaryPost?.staffName}」、机动「${row.flexPost?.staffName}」两岗在岗，` +
      `开场后客人进场，生成当天开场条。`,
      '开场确认 · 两岗已齐',
      { type: 'warning', confirmButtonText: '确认开场', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  openingDemandId.value = row.demandId
  try {
    await dutyApi.openVenue(row.demandId)
    ElMessage.success(`「${row.venueName}」已开场`)
    await loadDay()
  } catch (error) {
    // 两岗不齐被安保规则拦下：场地停在已锁定，不能开场
    ElMessage.error(resolveError(error, '开场失败'))
    await loadDay()
  } finally {
    openingDemandId.value = null
  }
}

onMounted(loadDay)
</script>

<template>
  <div class="duty-container">
    <div class="duty-header">
      <h3>开场当天值守</h3>
      <div class="date-bar">
        <span>活动日期：</span>
        <el-date-picker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择活动日期"
          @change="loadDay"
        />
        <el-button :loading="loading" @click="loadDay">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="rule-alert"
      title="安保规定：每块场地当天主值守、机动两岗都签到齐全，才能从已锁定变成可开场；机动岗空着不能开场，也不能绕过签到把已锁定直接改成可开场。值守中途撤岗导致两岗不齐时，已开场的退回已锁定、当天开场条作废，押金冻结不受影响。"
    />

    <div v-if="dayList.length === 0 && !loading" class="empty-state">
      <el-alert
        type="info"
        title="当天没有已锁定的活动"
        description="在「推荐结果」页确认场地并冻结押金后，活动日当天的场地会出现在这里，等待两岗签到。"
        show-icon
      />
    </div>

    <div v-else class="duty-list" v-loading="loading">
      <div v-for="row in dayList" :key="row.demandId" class="duty-card">
        <div class="duty-card-header">
          <div class="venue-info">
            <h4>{{ row.venueName }}</h4>
            <div class="meta">
              <span>需求：{{ row.demandName }}</span>
              <span>客户：{{ row.customerName }}</span>
              <span>活动日：{{ row.activityDate }}</span>
            </div>
          </div>
          <el-tag :type="statusMeta(row).type">{{ statusMeta(row).text }}</el-tag>
        </div>

        <div class="posts">
          <div v-for="panel in postPanels(row)" :key="panel.key" class="post-card">
            <div class="post-title">{{ panel.label }}</div>
            <div v-if="panel.view" class="post-body">
              <el-tag type="success" size="small">在岗</el-tag>
              <span class="staff">{{ panel.view.staffName }}</span>
              <span class="time">{{ formatTime(panel.view.signedAt) }} 签到</span>
              <el-button
                size="small"
                type="warning"
                plain
                :loading="withdrawingKey === `${row.demandId}-${panel.key}`"
                @click="withdraw(row, panel.key, panel.label, panel.view.staffName)"
              >撤岗</el-button>
            </div>
            <div v-else class="post-body">
              <el-tag type="info" size="small">待签到</el-tag>
              <el-button
                size="small"
                type="primary"
                plain
                @click="openSignDialog(row, panel.key, panel.label)"
              >签到</el-button>
            </div>
          </div>
        </div>

        <div class="open-bar">
          <template v-if="row.opened">
            <el-tag type="success" size="large">已开场</el-tag>
            <span class="time">{{ formatTime(row.openedAt) }} 开场</span>
          </template>
          <template v-else>
            <el-button
              type="danger"
              :disabled="!row.canOpen"
              :loading="openingDemandId === row.demandId"
              @click="openVenue(row)"
            >开场</el-button>
            <span v-if="row.canOpen" class="hint ok">两岗已齐，可以开场</span>
            <span v-else class="hint">两岗签到齐全才能开场（安保规定）</span>
          </template>
        </div>
      </div>
    </div>

    <el-dialog v-model="signDialogVisible" :title="`${signForm.postLabel}签到 · ${signForm.venueName}`" width="420px">
      <el-form label-width="90px">
        <el-form-item label="岗位">
          <el-input :model-value="signForm.postLabel" disabled />
        </el-form-item>
        <el-form-item label="签到人" required>
          <el-input v-model="signForm.staffName" placeholder="请输入签到人姓名" @keyup.enter="submitSign" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="同一岗位当天只能一人在岗；若该岗已被他人签上，会提示该岗已被占用。"
        />
      </el-form>
      <template #footer>
        <el-button @click="signDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="signSubmitting" @click="submitSign">签到</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.duty-container {
  max-width: 100%;
}

.duty-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.duty-header h3 {
  font-size: 18px;
  margin: 0;
}

.date-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rule-alert {
  margin-bottom: 16px;
}

.empty-state {
  margin-top: 40px;
}

.duty-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.duty-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px 20px;
}

.duty-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 14px;
}

.venue-info h4 {
  margin: 0 0 8px 0;
  font-size: 16px;
}

.meta {
  display: flex;
  gap: 16px;
  font-size: 14px;
  color: #606266;
  flex-wrap: wrap;
}

.posts {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.post-card {
  border: 1px dashed #dcdfe6;
  border-radius: 8px;
  padding: 12px 14px;
}

.post-title {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.post-body {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.staff {
  font-weight: 600;
}

.time {
  font-size: 13px;
  color: #909399;
}

.open-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f0f0;
  padding-top: 12px;
}

.hint {
  font-size: 13px;
  color: #909399;
}

.hint.ok {
  color: #67c23a;
}
</style>
