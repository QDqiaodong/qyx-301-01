<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  financeApi, invoiceApi, demandApi, resolveError,
  type CustomerAccount, type DepositTransaction, type Invoice, type ActivityDemand
} from '../api'

const activePane = ref<'accounts' | 'ledger' | 'invoices'>('accounts')
const accounts = ref<CustomerAccount[]>([])
const transactions = ref<DepositTransaction[]>([])
const invoices = ref<Invoice[]>([])
const loading = ref(false)

const rechargeDialogVisible = ref(false)
const rechargeSubmitting = ref(false)
const rechargeForm = reactive({ accountId: 0 as number, customerName: '', amount: 0, note: '' })

// 开票对话框：财务选需求开票；未结清/已有有效票会被后端拒并写明原因
const issueDialogVisible = ref(false)
const issueSubmitting = ref(false)
const issueForm = reactive({ demandId: null as number | null, operatorName: '' })
const issueDemands = ref<ActivityDemand[]>([])

const loadAccounts = async () => {
  loading.value = true
  try {
    const res = await financeApi.listAccounts()
    accounts.value = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载押金账户失败'))
  } finally {
    loading.value = false
  }
}

const loadTransactions = async () => {
  loading.value = true
  try {
    const res = await financeApi.listTransactions()
    transactions.value = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载押金流水失败'))
  } finally {
    loading.value = false
  }
}

const loadInvoices = async () => {
  loading.value = true
  try {
    const res = await invoiceApi.list()
    invoices.value = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载发票台账失败'))
  } finally {
    loading.value = false
  }
}

const handlePaneChange = (pane: string) => {
  if (pane === 'accounts') loadAccounts()
  else if (pane === 'ledger') loadTransactions()
  else loadInvoices()
}

const openRecharge = (account: CustomerAccount) => {
  rechargeForm.accountId = account.id!
  rechargeForm.customerName = account.customerName
  rechargeForm.amount = 0
  rechargeForm.note = ''
  rechargeDialogVisible.value = true
}

const submitRecharge = async () => {
  if (!rechargeForm.amount || rechargeForm.amount <= 0) {
    ElMessage.warning('充值金额必须大于0')
    return
  }
  rechargeSubmitting.value = true
  try {
    await financeApi.recharge(rechargeForm.accountId, rechargeForm.amount, rechargeForm.note)
    ElMessage.success(`已为「${rechargeForm.customerName}」充值 ¥${rechargeForm.amount}`)
    rechargeDialogVisible.value = false
    await loadAccounts()
  } catch (error) {
    ElMessage.error(resolveError(error, '充值失败'))
  } finally {
    rechargeSubmitting.value = false
  }
}

// ==================== 结算发票 ====================

const openIssueDialog = async () => {
  issueForm.demandId = null
  issueForm.operatorName = ''
  issueDialogVisible.value = true
  try {
    const res = await demandApi.getAll()
    issueDemands.value = res.data
  } catch (error) {
    ElMessage.error(resolveError(error, '加载需求列表失败'))
  }
}

const demandLabel = (d: ActivityDemand) =>
  `#${d.id} ${d.demandName}（${d.customerName}）`

const submitIssue = async () => {
  if (!issueForm.demandId) {
    ElMessage.warning('请选择要开票的需求')
    return
  }
  issueSubmitting.value = true
  try {
    const res = await invoiceApi.issue(issueForm.demandId, issueForm.operatorName || undefined)
    ElMessage.success(`已开结算发票「${res.data.invoiceNo}」，票面 ¥${res.data.amount}`)
    issueDialogVisible.value = false
    await loadInvoices()
  } catch (error) {
    // 未结清（还差哪笔没结）/已有有效票/非财务角色：后端会写明原因
    ElMessage.error(resolveError(error, '开票失败'))
  } finally {
    issueSubmitting.value = false
  }
}

const voidInvoice = async (invoice: Invoice) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `红字作废发票「${invoice.invoiceNo}」（¥${invoice.amount}）？作废后旧票号不能再当有效票去报，可重新开票。请留下作废原因：`,
      '红字作废',
      {
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        inputPlaceholder: '作废原因（必填，留痕）',
        inputValidator: (v: string) => (v && v.trim().length > 0) || '作废原因不能为空'
      }
    )
    await invoiceApi.void(invoice.id!, value.trim())
    ElMessage.success(`发票「${invoice.invoiceNo}」已红字作废`)
    await loadInvoices()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(resolveError(error, '作废失败'))
  }
}

const basisText = (basis: string) =>
  basis === 'OPENED' ? '活动已开场' : basis === 'REFUNDED' ? '押金已全额退完' : basis

const txTypeMeta = (tx: DepositTransaction) => {
  switch (tx.type) {
    case 'RECHARGE': return { text: '充值入账', type: 'success' as const }
    case 'FREEZE': return { text: '冻结押金', type: 'warning' as const }
    case 'REFUND': return { text: '取消退押', type: 'primary' as const }
    case 'UNFREEZE': return { text: '全额解冻', type: 'info' as const }
    default: return { text: tx.type, type: 'info' as const }
  }
}

const totalFrozen = () =>
  accounts.value.reduce((sum, a) => sum + (Number(a.frozenBalance) || 0), 0)
const totalAvailable = () =>
  accounts.value.reduce((sum, a) => sum + (Number(a.availableBalance) || 0), 0)

onMounted(loadAccounts)
</script>

<template>
  <div class="finance-container">
    <h3>押金财务台账</h3>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="rule-alert"
      title="客户口头看中场地后先冻结押金，冻结成功才进入待办活动；取消活动按距活动日远近固定退押：≥3天全退、三天内半退、当天不退。退回比例由系统按日期自动核算，任何人（含销售）都不能手工修改。结算发票只有财务能开：活动已开场或押金全额退完才开得出，票面金额=已结清的冻结/实退金额；还在冻结中的需求开不了票。"
    />

    <el-tabs v-model="activePane" @tab-change="handlePaneChange">
      <el-tab-pane label="客户押金账户" name="accounts">
        <el-table v-loading="loading" :data="accounts" border>
          <el-table-column type="index" label="#" width="60" />
          <el-table-column prop="customerName" label="客户" min-width="120" />
          <el-table-column prop="customerPhone" label="联系电话" min-width="130">
            <template #default="scope">{{ scope.row.customerPhone || '-' }}</template>
          </el-table-column>
          <el-table-column label="可用余额" width="140">
            <template #default="scope">
              <span class="money available">¥{{ scope.row.availableBalance }}</span>
            </template>
          </el-table-column>
          <el-table-column label="已冻结押金" width="140">
            <template #default="scope">
              <span class="money frozen">¥{{ scope.row.frozenBalance }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="最近变动" width="180" />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="scope">
              <el-button type="primary" size="small" @click="openRecharge(scope.row)">押金充值</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="accounts.length > 0" class="totals">
          合计可用余额 <b class="available">¥{{ totalAvailable().toFixed(2) }}</b>
          ｜合计冻结押金 <b class="frozen">¥{{ totalFrozen().toFixed(2) }}</b>
        </div>
      </el-tab-pane>

      <el-tab-pane label="押金流水（冻结/退回/没收）" name="ledger">
        <el-table v-loading="loading" :data="transactions" border size="small">
          <el-table-column prop="createdAt" label="时间" width="170" />
          <el-table-column label="类型" width="100">
            <template #default="scope">
              <el-tag :type="txTypeMeta(scope.row).type" size="small">{{ txTypeMeta(scope.row).text }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="customerName" label="客户" min-width="100" />
          <el-table-column prop="demandName" label="需求/活动" min-width="140">
            <template #default="scope">{{ scope.row.demandName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="venueName" label="场地" min-width="110">
            <template #default="scope">{{ scope.row.venueName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="activityDate" label="活动日" width="120">
            <template #default="scope">
              {{ scope.row.activityDate ? String(scope.row.activityDate).slice(0, 10) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="金额" width="100">
            <template #default="scope">¥{{ scope.row.amount }}</template>
          </el-table-column>
          <el-table-column label="退回比例" width="90">
            <template #default="scope">
              <el-tag v-if="scope.row.refundRate != null" size="small"
                      :type="scope.row.refundRate === 100 ? 'success' : scope.row.refundRate === 50 ? 'warning' : 'danger'">
                {{ scope.row.refundRate }}%
              </el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="退回" width="100">
            <template #default="scope">
              <span class="money refund">¥{{ scope.row.refundAmount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="没收" width="100">
            <template #default="scope">
              <span :class="{ 'money forfeit': Number(scope.row.forfeitAmount) > 0 }">
                ¥{{ scope.row.forfeitAmount }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="结算发票" width="160">
            <template #default="scope">
              <el-tag v-if="scope.row.invoiceNo" type="success" size="small">
                {{ scope.row.invoiceNo }}（¥{{ scope.row.invoiceAmount }}）
              </el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="档位/原因" min-width="280" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="结算发票台账" name="invoices">
        <div class="invoice-toolbar">
          <el-button type="primary" @click="openIssueDialog">开具结算发票</el-button>
          <span class="hint">
            只有财务能开票；活动已开场或押金全额退完才开得出，还在冻结中的需求开票会被拒并写明哪笔没结。
            重开前须先把旧票红字作废并留作废原因。
          </span>
        </div>
        <el-table v-loading="loading" :data="invoices" border size="small">
          <el-table-column prop="invoiceNo" label="发票号" width="170" />
          <el-table-column label="状态" width="100">
            <template #default="scope">
              <el-tag :type="scope.row.status === 'VALID' ? 'success' : 'danger'" size="small">
                {{ scope.row.status === 'VALID' ? '有效' : '红字作废' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="demandName" label="需求/活动" min-width="140">
            <template #default="scope">#{{ scope.row.demandId }} {{ scope.row.demandName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="customerName" label="票面客户（=押金账户）" min-width="130" />
          <el-table-column label="票面金额" width="110">
            <template #default="scope">
              <span class="money">¥{{ scope.row.amount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结清依据" width="130">
            <template #default="scope">{{ basisText(scope.row.settleBasis) }}</template>
          </el-table-column>
          <el-table-column prop="issuedBy" label="开票人" width="100">
            <template #default="scope">{{ scope.row.issuedBy || '-' }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="开票时间" width="170" />
          <el-table-column label="作废原因" min-width="180" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.voidReason || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.status === 'VALID'"
                type="danger"
                size="small"
                @click="voidInvoice(scope.row)"
              >红字作废</el-button>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="rechargeDialogVisible" title="客户押金充值" width="440px">
      <el-form label-width="100px">
        <el-form-item label="客户">
          <el-input :model-value="rechargeForm.customerName" disabled />
        </el-form-item>
        <el-form-item label="充值金额" required>
          <el-input-number v-model="rechargeForm.amount" :min="0.01" :precision="2" :step="500" />
          <span class="hint">充值后客户可用于冻结押金的可用余额增加</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rechargeForm.note" placeholder="如：线下收取押金转充值" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="rechargeSubmitting" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="issueDialogVisible" title="开具结算发票（财务）" width="480px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="issue-alert"
        title="活动已开场、或押金已全额退完的需求才能开票；票面金额=已结清的冻结/实退金额，票面客户自动取押金账户持有人。还在冻结中的需求开票会失败并写明哪笔没结。"
      />
      <el-form label-width="100px">
        <el-form-item label="开票需求" required>
          <el-select
            v-model="issueForm.demandId"
            filterable
            placeholder="选择需求"
            style="width: 100%"
          >
            <el-option
              v-for="d in issueDemands"
              :key="d.id"
              :value="d.id!"
              :label="demandLabel(d)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="开票人">
          <el-input v-model="issueForm.operatorName" placeholder="财务操作人姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="issueSubmitting" @click="submitIssue">确认开票</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.finance-container {
  max-width: 100%;
}

.finance-container h3 {
  font-size: 18px;
  margin-bottom: 16px;
}

.rule-alert {
  margin-bottom: 16px;
}

.money {
  font-weight: 600;
}

.money.available {
  color: #67c23a;
}

.money.frozen {
  color: #e6a23c;
}

.money.refund {
  color: #409eff;
}

.money.forfeit {
  color: #f56c6c;
  font-weight: 700;
}

.totals {
  margin-top: 12px;
  font-size: 14px;
  color: #606266;
  text-align: right;
}

.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}

.invoice-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.issue-alert {
  margin-bottom: 16px;
}
</style>
