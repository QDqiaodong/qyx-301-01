<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  demandApi,
  type ActivityDemand, type LockRecord, type SiteVisit, type DepositTransaction, type Invoice
} from '../api'

const demands = ref<ActivityDemand[]>([])
const expandRow = ref<number | null>(null)
const recommendCache = ref<Map<number, any[]>>(new Map())
const lockCache = ref<Map<number, LockRecord[]>>(new Map())
const visitCache = ref<Map<number, SiteVisit | null>>(new Map())
const depositCache = ref<Map<number, DepositTransaction[]>>(new Map())
const invoiceCache = ref<Map<number, Invoice[]>>(new Map())

const loadDemands = async () => {
  const res = await demandApi.getAll()
  demands.value = res.data
}

const toggleExpand = async (demandId: number) => {
  if (expandRow.value === demandId) {
    expandRow.value = null
    return
  }

  expandRow.value = demandId

  if (!lockCache.value.has(demandId)) {
    try {
      const res = await demandApi.getLockHistory(demandId)
      lockCache.value.set(demandId, res.data)
    } catch {
      lockCache.value.set(demandId, [])
    }
  }

  if (!visitCache.value.has(demandId)) {
    try {
      const res = await demandApi.getSiteVisit(demandId)
      visitCache.value.set(demandId, res.data)
    } catch {
      visitCache.value.set(demandId, null)
    }
  }

  if (!depositCache.value.has(demandId)) {
    try {
      const res = await demandApi.getDepositTransactions(demandId)
      depositCache.value.set(demandId, res.data)
    } catch {
      depositCache.value.set(demandId, [])
    }
  }

  if (!invoiceCache.value.has(demandId)) {
    try {
      const res = await demandApi.getInvoices(demandId)
      invoiceCache.value.set(demandId, res.data)
    } catch {
      invoiceCache.value.set(demandId, [])
    }
  }

  // 锁定破裂（待重配）后旧名单已作废，不再展示破裂前的推荐结果
  const demand = demands.value.find(d => d.id === demandId)
  const stale = demand?.matchStatus === 4
  if (!stale && !recommendCache.value.has(demandId)) {
    try {
      const res = await demandApi.getRecommendResults(demandId)
      recommendCache.value.set(demandId, res.data)
    } catch {
      recommendCache.value.set(demandId, [])
    }
  }
  if (stale) {
    recommendCache.value.set(demandId, [])
  }
}

const getStatusText = (status: number, locked?: number, opened?: number) => {
  if (locked === 1 && opened === 1) return { text: '已开场', type: 'success' }
  if (locked === 1) return { text: '已锁定（待办活动）', type: 'success' }
  switch (status) {
    case 0: return { text: '待计算', type: 'info' }
    case 1: return { text: '已匹配', type: 'success' }
    case 2: return { text: '无匹配', type: 'warning' }
    case 3: return { text: '部分匹配', type: 'warning' }
    case 4: return { text: '待重配', type: 'danger' }
    case 6: return { text: '已取消（已退押）', type: 'info' }
    default: return { text: '未知', type: 'info' }
  }
}

const lockStatusText = (status: string) => {
  if (status === 'LOCKED') return { text: '锁定中', type: 'success' }
  if (status === 'BROKEN') return { text: '已破裂', type: 'danger' }
  if (status === 'CANCELED') return { text: '已取消', type: 'danger' }
  return { text: '已解除', type: 'info' }
}

const depositTypeMeta = (tx: DepositTransaction) => {
  switch (tx.type) {
    case 'RECHARGE': return { text: '充值入账', type: 'success' }
    case 'FREEZE': return { text: '冻结押金', type: 'warning' }
    case 'REFUND': return { text: '取消退押', type: 'primary' }
    case 'UNFREEZE': return { text: '全额解冻', type: 'info' }
    default: return { text: tx.type, type: 'info' }
  }
}

const activeInvoice = (demandId: number) =>
  (invoiceCache.value.get(demandId) || []).find(i => i.status === 'VALID') || null

const invoiceBasisText = (invoice: Invoice) =>
  invoice.basisType === 'FROZEN_OPENED'
    ? '活动已开场，按冻结额结清'
    : '押金全额实退完，按实退额结清'

const slotText = (slot: string) => slot === 'MORNING' ? '上午' : '下午'

onMounted(loadDemands)
</script>

<template>
  <div class="history-container">
    <h3>历史需求记录</h3>

    <div v-if="demands.length === 0" class="empty-state">
      <el-alert type="info" title="暂无历史记录" description="请先提交需求" show-icon />
    </div>

    <div v-else class="history-list">
      <div v-for="demand in demands" :key="demand.id" class="history-card">
        <div class="card-header" @click="toggleExpand(demand.id!)">
          <div class="header-left">
            <h4>{{ demand.demandName }}</h4>
            <div class="meta">
              <span>客户：{{ demand.customerName }}</span>
              <span>{{ demand.expectedPeople }}人</span>
              <span>{{ demand.activityCategory }}</span>
              <el-tag v-if="demand.locked === 1" type="success" size="small">
                已锁定：{{ demand.lockedVenueName }}（押金¥{{ demand.depositAmount ?? 0 }}）
              </el-tag>
              <el-tag v-if="demand.locked === 1 && demand.opened === 1" type="danger" size="small">
                当天已开场
              </el-tag>
              <el-tag
                v-if="demand.currentInvoiceStatus === 'VALID'"
                type="success"
                size="small"
                effect="dark"
              >有效发票 {{ demand.currentInvoiceNo }} ¥{{ demand.currentInvoiceAmount }}</el-tag>
            </div>
          </div>
          <div class="header-right">
            <el-tag :type="getStatusText(demand.matchStatus, demand.locked, demand.opened).type">
              {{ getStatusText(demand.matchStatus, demand.locked, demand.opened).text }}
            </el-tag>
            <el-icon :class="{ expanded: expandRow === demand.id }">
              <ArrowRight />
            </el-icon>
          </div>
        </div>

        <div v-if="expandRow === demand.id" class="card-detail">
          <el-alert
            v-if="demand.locked === 1"
            type="success"
            :closable="false"
            show-icon
            class="detail-alert"
            :title="`已冻结押金 ¥${demand.depositAmount ?? 0} 并锁定场地「${demand.lockedVenueName}」（待办活动），期望日期、人数、预算上限、必备设施在锁定期间不能直接修改；要改数字须先解除锁定（全额退押）并作废推荐，再按新条件重算。`"
          />
          <el-alert
            v-if="demand.matchStatus === 6"
            type="info"
            :closable="false"
            show-icon
            class="detail-alert"
            :title="`活动已取消并完成退押结算${demand.depositRefundSummary ? '：' + demand.depositRefundSummary : ''}`"
            description="取消后需求为终态，不能再算推荐占场；如需继续办活动请重新提交需求。"
          />
          <el-alert
            v-if="demand.matchStatus === 4 && demand.depositRefundSummary"
            type="warning"
            :closable="false"
            show-icon
            class="detail-alert"
            :title="`原冻结押金已结算：${demand.depositRefundSummary}`"
          />
          <el-alert
            v-if="demand.matchStatus === 4"
            type="error"
            :closable="false"
            show-icon
            class="detail-alert"
            :title="`锁定已破裂，需求待重配${demand.lockBreakReason ? '。破裂原因：' + demand.lockBreakReason : ''}`"
            description="破裂前的旧推荐名单已作废，不再作为有效结果。"
          />

          <div class="detail-section">
            <h5>需求详情</h5>
            <div class="detail-grid">
              <div class="detail-item">
                <span>联系电话：</span>{{ demand.customerPhone || '-' }}
              </div>
              <div class="detail-item">
                <span>期望日期：</span>{{ demand.expectedDate || '-' }}
              </div>
              <div class="detail-item">
                <span>预算范围：</span>¥{{ demand.budgetMin }} - ¥{{ demand.budgetMax }}
              </div>
              <div class="detail-item">
                <span>必备设施：</span>{{ demand.requiredFacilities || '-' }}
              </div>
              <div class="detail-item full">
                <span>特殊要求：</span>{{ demand.specialRequirements || '-' }}
              </div>
            </div>
          </div>

          <div class="detail-section">
            <h5>踩点试场登记</h5>
            <div v-if="visitCache.get(demand.id!)" class="visit-box">
              <el-descriptions :column="4" border size="small">
                <el-descriptions-item label="试场场地">
                  {{ visitCache.get(demand.id!)?.venueName }}
                </el-descriptions-item>
                <el-descriptions-item label="试场日期">
                  {{ visitCache.get(demand.id!)?.visitDate }}
                </el-descriptions-item>
                <el-descriptions-item label="时段">
                  {{ slotText(visitCache.get(demand.id!)?.timeSlot || '') }}
                </el-descriptions-item>
                <el-descriptions-item label="预留人数">
                  {{ visitCache.get(demand.id!)?.reservedPeople }}人
                </el-descriptions-item>
              </el-descriptions>
            </div>
            <el-alert v-else type="info" :closable="false" title="该需求尚未登记踩点试场" />
          </div>

          <div class="detail-section">
            <h5>锁定历史</h5>
            <el-table v-if="(lockCache.get(demand.id!) || []).length > 0" :data="lockCache.get(demand.id!)" border size="small">
              <el-table-column prop="venueName" label="场地" min-width="120" />
              <el-table-column label="状态" width="90">
                <template #default="scope">
                  <el-tag :type="lockStatusText(scope.row.status).type" size="small">
                    {{ lockStatusText(scope.row.status).text }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="破裂/解除原因" min-width="260">
                <template #default="scope">{{ scope.row.breakReason || '-' }}</template>
              </el-table-column>
              <el-table-column prop="lockedAt" label="锁定时间" width="170" />
              <el-table-column label="结束时间" width="170">
                <template #default="scope">{{ scope.row.releasedAt || '-' }}</template>
              </el-table-column>
            </el-table>
            <el-alert v-else type="info" :closable="false" title="该需求尚无锁定记录" />
          </div>

          <div class="detail-section">
            <h5>押金冻结 / 退押流水</h5>
            <el-table v-if="(depositCache.get(demand.id!) || []).length > 0" :data="depositCache.get(demand.id!)" border size="small">
              <el-table-column label="类型" width="100">
                <template #default="scope">
                  <el-tag :type="depositTypeMeta(scope.row).type" size="small">
                    {{ depositTypeMeta(scope.row).text }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="venueName" label="场地" min-width="100">
                <template #default="scope">{{ scope.row.venueName || '-' }}</template>
              </el-table-column>
              <el-table-column label="冻结金额" width="100">
                <template #default="scope">¥{{ scope.row.amount }}</template>
              </el-table-column>
              <el-table-column label="退回比例" width="90">
                <template #default="scope">
                  {{ scope.row.refundRate == null ? '-' : scope.row.refundRate + '%' }}
                </template>
              </el-table-column>
              <el-table-column label="退回" width="90">
                <template #default="scope">¥{{ scope.row.refundAmount }}</template>
              </el-table-column>
              <el-table-column label="没收" width="90">
                <template #default="scope">¥{{ scope.row.forfeitAmount }}</template>
              </el-table-column>
              <el-table-column label="对应有效发票" min-width="165">
                <template #default="scope">
                  <el-tag v-if="scope.row.currentInvoiceStatus === 'VALID'" type="success" size="small">
                    {{ scope.row.currentInvoiceNo }} ¥{{ scope.row.currentInvoiceAmount }}
                  </el-tag>
                  <span v-else style="color: #c0c4cc; font-size: 12px">未挂有效票</span>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="说明" min-width="240" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="时间" width="170" />
            </el-table>
            <el-alert v-else type="info" :closable="false" title="该需求尚无押金流水（未冻结过押金）" />
          </div>

          <div class="detail-section">
            <h5>结算发票</h5>
            <el-alert
              v-if="activeInvoice(demand.id!)"
              type="success"
              :closable="false"
              show-icon
              class="detail-alert"
              :title="`当前有效发票：${activeInvoice(demand.id!)?.invoiceNo}，票面金额 ¥${activeInvoice(demand.id!)?.amount}，客户（押金账户户主）：${activeInvoice(demand.id!)?.customerName}（${invoiceBasisText(activeInvoice(demand.id!)!)}）`"
              description="该票号与票面金额与发票台账、押金流水上挂的是同一张；旧票已作废成红字后，这里只显示新开的有效票。"
            />
            <el-alert
              v-else
              type="info"
              :closable="false"
              show-icon
              title="该需求当前没有有效发票：还在冻结中没开场也没退完的不能开；若之前开过票，则旧票已作废成红字、暂无新票。"
            />
            <el-table
              v-if="(invoiceCache.get(demand.id!) || []).length > 0"
              :data="invoiceCache.get(demand.id!)"
              border
              size="small"
              style="margin-top: 10px"
            >
              <el-table-column prop="invoiceNo" label="发票号" min-width="160">
                <template #default="scope">
                  <span :style="{ color: scope.row.status === 'RED_VOID' ? '#f56c6c' : '', fontWeight: scope.row.status === 'RED_VOID' ? 700 : 400 }">
                    {{ scope.row.invoiceNo }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="scope">
                  <el-tag :type="scope.row.status === 'VALID' ? 'success' : 'danger'" size="small">
                    {{ scope.row.status === 'VALID' ? '有效票' : '红字作废' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="customerName" label="客户" min-width="100" />
              <el-table-column label="票面金额" width="100">
                <template #default="scope">¥{{ scope.row.amount }}</template>
              </el-table-column>
              <el-table-column label="开票依据" min-width="170">
                <template #default="scope">{{ invoiceBasisText(scope.row) }}</template>
              </el-table-column>
              <el-table-column prop="issuedAt" label="开票时间" width="170" />
              <el-table-column label="作废原因" min-width="200">
                <template #default="scope">
                  <span v-if="scope.row.status === 'RED_VOID'" style="color: #f56c6c">
                    {{ scope.row.voidReason }}（{{ scope.row.voidedAt }}）
                  </span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="detail-section">
            <h5>推荐结果推演记录</h5>
            <div v-if="demand.matchStatus === 4" class="no-recommend">
              <el-alert
                type="warning"
                :closable="false"
                title="旧推荐已随锁定破裂作废"
                description="该名单不再作为有效结果，请在「推荐结果」页按新条件重新计算。"
              />
            </div>
            <div v-else-if="(recommendCache.get(demand.id!) || []).length === 0" class="no-recommend">
              <el-alert type="info" title="暂无推荐记录" description="该需求尚未计算推荐" show-icon :closable="false" />
            </div>
            <el-table v-else :data="recommendCache.get(demand.id!)" border size="small">
              <el-table-column prop="recommendOrder" label="排名" width="60" />
              <el-table-column prop="venueName" label="场地名称" />
              <el-table-column prop="matchScore" label="匹配度" width="100" />
              <el-table-column prop="reason" label="匹配说明" show-overflow-tooltip />
            </el-table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.history-container {
  max-width: 100%;
}

.history-container h3 {
  font-size: 18px;
  margin-bottom: 16px;
}

.empty-state {
  margin-top: 40px;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.history-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: #fafafa;
  cursor: pointer;
  transition: background 0.3s;
}

.card-header:hover {
  background: #f0f0f0;
}

.header-left {
  flex: 1;
}

.header-left h4 {
  margin: 0 0 8px 0;
  font-size: 16px;
}

.meta {
  display: flex;
  gap: 16px;
  font-size: 14px;
  color: #606266;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-right .expanded {
  transform: rotate(90deg);
}

.card-detail {
  padding: 20px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.detail-alert {
  margin-bottom: 16px;
}

.detail-section {
  margin-bottom: 20px;
}

.detail-section:last-child {
  margin-bottom: 0;
}

.detail-section h5 {
  font-size: 14px;
  margin: 0 0 12px 0;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.detail-item {
  font-size: 14px;
  color: #606266;
}

.detail-item.full {
  grid-column: span 2;
}

.visit-box {
  margin-bottom: 4px;
}

.no-recommend {
  margin-bottom: 10px;
}
</style>
