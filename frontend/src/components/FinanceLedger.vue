<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  financeApi, resolveError,
  type CustomerAccount, type DepositTransaction
} from '../api'
import SettlementInvoice from './SettlementInvoice.vue'

const activePane = ref<'accounts' | 'ledger' | 'invoices'>('invoices')
const accounts = ref<CustomerAccount[]>([])
const transactions = ref<DepositTransaction[]>([])
const loading = ref(false)

const invoicePanelRef = ref<InstanceType<typeof SettlementInvoice> | null>(null)

const rechargeDialogVisible = ref(false)
const rechargeSubmitting = ref(false)
const rechargeForm = reactive({ accountId: 0 as number, customerName: '', amount: 0, note: '' })

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

const handlePaneChange = (pane: string) => {
  if (pane === 'accounts') loadAccounts()
  else if (pane === 'ledger') loadTransactions()
  else invoicePanelRef.value?.reload()
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
      title="客户口头看中场地后先冻结押金，冻结成功才进入待办活动；取消活动按距活动日远近固定退押：≥3天全退、三天内半退、当天不退。退回比例由系统按日期自动核算，任何人（含销售）都不能手工修改。"
    />

    <el-tabs v-model="activePane" @tab-change="handlePaneChange">
      <el-tab-pane label="结算发票" name="invoices">
        <SettlementInvoice ref="invoicePanelRef" />
      </el-tab-pane>

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
          <el-table-column label="对应有效发票" min-width="175">
            <template #default="scope">
              <template v-if="scope.row.currentInvoiceStatus === 'VALID'">
                <el-tag type="success" size="small">{{ scope.row.currentInvoiceNo }}</el-tag>
                <span class="money invoice">¥{{ scope.row.currentInvoiceAmount }}</span>
              </template>
              <span v-else class="no-invoice">未挂有效票</span>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="档位/原因" min-width="280" show-overflow-tooltip />
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

.money.invoice {
  color: #67c23a;
  margin-left: 8px;
}

.no-invoice {
  color: #c0c4cc;
  font-size: 12px;
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
</style>
