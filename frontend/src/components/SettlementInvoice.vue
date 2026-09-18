<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  demandApi, invoiceApi, resolveError, roleStore,
  type ActivityDemand, type Invoice
} from '../api'

const demands = ref<ActivityDemand[]>([])
const invoices = ref<Invoice[]>([])
const loading = ref(false)
const currentRole = ref(roleStore.getRole())

const voidDialogVisible = ref(false)
const voidSubmitting = ref(false)
const voidForm = reactive({ invoiceId: 0 as number, invoiceNo: '', reason: '' })

const isFinance = computed(() => currentRole.value === 'FINANCE')

const basisMeta = (basisType?: string | null) => {
  if (basisType === 'FROZEN_OPENED') {
    return { text: '活动已开场·按冻结额', type: 'danger' as const }
  }
  return { text: '押金全额实退·按实退额', type: 'success' as const }
}

const invoiceStatusMeta = (invoice: Invoice) =>
  invoice.status === 'VALID'
    ? { text: '有效票', type: 'success' as const }
    : { text: '红字作废', type: 'danger' as const }

/**
 * 可开票的需求：已经开场（冻结随开场结清），或押金已经全额退回（已取消/已解除/已破裂）。
 * 没冻结过、还在冻结中没开场的不出现在这里；能不能开最终以后端按流水判断为准。
 */
const invoiceableDemands = computed(() =>
  demands.value.filter((d) => {
    const hasFrozenHistory = d.depositFreezeId != null || !!d.depositRefundSummary
    const opened = d.opened === 1
    const settledAndUnlocked = d.locked !== 1 && !!d.depositRefundSummary
    return hasFrozenHistory && (opened || settledAndUnlocked)
  })
)

const loadInvoices = async () => {
  loading.value = true
  try {
    const res = await invoiceApi.listAll()
    invoices.value = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载发票台账失败'))
  } finally {
    loading.value = false
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    const [demandRes, invoiceRes] = await Promise.all([
      demandApi.getAll(),
      invoiceApi.listAll()
    ])
    demands.value = demandRes.data
    invoices.value = invoiceRes.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载开票数据失败'))
  } finally {
    loading.value = false
  }
}

const activeInvoiceOf = (demandId: number) =>
  invoices.value.find((i) => i.demandId === demandId && i.status === 'VALID') || null

const issueInvoice = async (demand: ActivityDemand) => {
  if (!isFinance.value) {
    // 销售点开票：前端先提示，真正的拦截在后端（403）
    ElMessage.error('开票失败：只有财务角色才能开具结算发票，请切换到财务角色后操作')
    return
  }
  const active = demand.id != null ? activeInvoiceOf(demand.id) : null
  if (active) {
    ElMessage.warning(
      `该需求已有有效发票 ${active.invoiceNo}（¥${active.amount}），不能重复开票；如需重开请先把旧票作废成红字`
    )
    return
  }
  try {
    await ElMessageBox.confirm(
      `将为需求「${demand.demandName}」开具结算发票。\n` +
      '票面金额、客户名由系统按押金流水与押金账户自动核算（金额不可手填）：\n' +
      '· 活动已开场：票面金额=冻结流水的冻结金额；\n' +
      '· 押金全额实退：票面金额=结算流水的实退金额。\n' +
      '还在冻结中没开场也没退完的需求会被直接拒绝。',
      '财务开具结算发票',
      { type: 'warning', confirmButtonText: '确认开票', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await invoiceApi.issue(demand.id!)
    ElMessage.success(`开票成功：发票号 ${res.data.invoiceNo}，票面金额 ¥${res.data.amount}（客户：${res.data.customerName}）`)
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveError(error, '开票失败'))
  }
}

const openVoidDialog = (invoice: Invoice) => {
  if (!isFinance.value) {
    ElMessage.error('作废失败：只有财务角色才能作废发票')
    return
  }
  voidForm.invoiceId = invoice.id!
  voidForm.invoiceNo = invoice.invoiceNo
  voidForm.reason = ''
  voidDialogVisible.value = true
}

const submitVoid = async () => {
  if (!voidForm.reason.trim()) {
    ElMessage.warning('作废必须填写作废原因（红字票需要留痕）')
    return
  }
  voidSubmitting.value = true
  try {
    await invoiceApi.voidInvoice(voidForm.invoiceId, voidForm.reason.trim())
    ElMessage.success(
      `发票号 ${voidForm.invoiceNo} 已作废成红字票，旧票号不能再当有效票报销；现在可以为该需求重新开票`
    )
    voidDialogVisible.value = false
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveError(error, '作废失败'))
  } finally {
    voidSubmitting.value = false
  }
}

onMounted(loadAll)
defineExpose({ reload: loadAll, loadInvoices })
</script>

<template>
  <div class="invoice-panel">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="rule-alert"
      title="结算发票按财务规矩与押金结清状态硬挂钩：只有活动已经开场（票面=已结清冻结额），或冻结押金已经全额实退回客户账户（票面=实退额）才能开。还在冻结中、没开场也没退完的不能开；销售锁场当天先开票报销的诉求不予放行。"
    >
      <template #default>
        <div>
          票面金额只按押金流水核算、客户名必须与押金账户户主一致；一条需求同一时刻只有一张有效票，
          重开须先把旧票作废成<span class="red-text">红字</span>并填写原因。发票台账、押金流水、需求详情看到的是同一张有效票号与同一金额。
        </div>
      </template>
    </el-alert>

    <el-alert
      v-if="!isFinance"
      type="error"
      :closable="false"
      show-icon
      class="role-alert"
      title="当前是销售角色：不能开具或作废发票（后端会以 403 拒绝并提示只有财务能开）。需要开票请在页顶切换到财务角色。"
    />

    <!-- 可开票需求 -->
    <div class="section">
      <div class="section-head">
        <h4>需求结算开票</h4>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="invoiceableDemands" border size="small">
        <el-table-column prop="demandName" label="需求/活动" min-width="150" />
        <el-table-column prop="customerName" label="押金账户客户" min-width="110" />
        <el-table-column label="场地/活动日" min-width="160">
          <template #default="scope">
            {{ scope.row.lockedVenueName || '-' }}
            <span v-if="scope.row.expectedDate">（{{ String(scope.row.expectedDate).slice(0, 10) }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="结清状态" min-width="150">
          <template #default="scope">
            <el-tag v-if="scope.row.opened === 1" type="danger" size="small">活动已开场</el-tag>
            <el-tag v-else type="success" size="small">押金已全额实退</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前有效发票" min-width="190">
          <template #default="scope">
            <template v-if="activeInvoiceOf(scope.row.id!)">
              <el-tag type="success" size="small">
                {{ activeInvoiceOf(scope.row.id!)?.invoiceNo }}
              </el-tag>
              <span class="inv-amount">¥{{ activeInvoiceOf(scope.row.id!)?.amount }}</span>
            </template>
            <span v-else class="muted">未开票</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="scope">
            <el-button
              v-if="!activeInvoiceOf(scope.row.id!)"
              type="primary"
              size="small"
              @click="issueInvoice(scope.row)"
            >开具结算发票</el-button>
            <el-button
              v-else
              type="danger"
              size="small"
              plain
              :disabled="!isFinance"
              @click="openVoidDialog(activeInvoiceOf(scope.row.id!)!)"
            >作废红字后重开</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert
        v-if="invoiceableDemands.length === 0"
        type="info"
        :closable="false"
        class="empty-alert"
        title="暂无可开票需求：还没有需求完成开场，也没有冻结押金全额实退完。仍在冻结中的需求不能开票。"
      />
    </div>

    <!-- 发票台账 -->
    <div class="section">
      <h4>发票台账（有效票 / 红字作废票）</h4>
      <el-table :data="invoices" border size="small">
        <el-table-column prop="invoiceNo" label="发票号" min-width="165">
          <template #default="scope">
            <span :class="{ 'red-text': scope.row.status === 'RED_VOID' }">
              {{ scope.row.invoiceNo }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="scope">
            <el-tag :type="invoiceStatusMeta(scope.row).type" size="small">
              {{ invoiceStatusMeta(scope.row).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户（押金账户户主）" min-width="150" />
        <el-table-column prop="demandName" label="需求/活动" min-width="140" />
        <el-table-column prop="venueName" label="场地" min-width="100">
          <template #default="scope">{{ scope.row.venueName || '-' }}</template>
        </el-table-column>
        <el-table-column label="票面金额" width="110">
          <template #default="scope">
            <span class="inv-amount" :class="{ 'red-text': scope.row.status === 'RED_VOID' }">
              ¥{{ scope.row.amount }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="开票依据" min-width="175">
          <template #default="scope">
            <el-tag :type="basisMeta(scope.row.basisType).type" size="small">
              {{ basisMeta(scope.row.basisType).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="basisReason" label="对应冻结/退回流水" min-width="300" show-overflow-tooltip />
        <el-table-column prop="issuedAt" label="开票时间" width="170" />
        <el-table-column prop="issuedBy" label="开票人" width="100" />
        <el-table-column label="作废信息" min-width="230">
          <template #default="scope">
            <template v-if="scope.row.status === 'RED_VOID'">
              <div class="red-text">原因：{{ scope.row.voidReason }}</div>
              <div class="muted">{{ scope.row.voidedAt }}｜{{ scope.row.voidedBy }}</div>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === 'VALID'"
              type="danger"
              size="small"
              plain
              :disabled="!isFinance"
              @click="openVoidDialog(scope.row)"
            >作废红字</el-button>
            <span v-else class="muted">已作废</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="voidDialogVisible" title="发票作废（红字）" width="480px">
      <el-form label-width="100px">
        <el-form-item label="作废发票号">
          <el-input :model-value="voidForm.invoiceNo" disabled />
        </el-form-item>
        <el-form-item label="作废原因" required>
          <el-input
            v-model="voidForm.reason"
            type="textarea"
            :rows="3"
            placeholder="如：客户抬头开错/票面信息有误，需要重开（必填，将随红字票留痕）"
          />
        </el-form-item>
        <el-alert
          type="error"
          :closable="false"
          title="作废后该发票号立即失效，不能再当有效票报销；原票保留为红字票留痕，之后可为该需求重新开具新票。"
        />
      </el-form>
      <template #footer>
        <el-button @click="voidDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="voidSubmitting" @click="submitVoid">确认作废成红字</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.invoice-panel {
  margin-top: 18px;
}

.rule-alert,
.role-alert {
  margin-bottom: 14px;
}

.section {
  margin-bottom: 22px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section h4 {
  font-size: 15px;
  margin: 0 0 10px 0;
}

.inv-amount {
  font-weight: 700;
  margin-left: 8px;
  color: #409eff;
}

.red-text {
  color: #f56c6c;
  font-weight: 700;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.empty-alert {
  margin-top: 10px;
}
</style>
