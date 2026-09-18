<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import VenueManagement from './components/VenueManagement.vue'
import DemandSubmission from './components/DemandSubmission.vue'
import RecommendResult from './components/RecommendResult.vue'
import EventDayDuty from './components/EventDayDuty.vue'
import HistoryRecords from './components/HistoryRecords.vue'
import FinanceLedger from './components/FinanceLedger.vue'
import { roleStore, type OperatorRole } from './api'

const activeTab = ref('demand')

// 当前操作角色：销售（默认）/ 财务。开票、作废按钮只有切到财务才放行；
// 角色随请求头带给后端，销售角色即使调接口也会被 403 挡下。
const currentRole = ref<OperatorRole>(roleStore.getRole())

const changeRole = (role: OperatorRole) => {
  if (role === currentRole.value) return
  currentRole.value = role
  roleStore.setRole(role)
  ElMessage.success(
    role === 'FINANCE'
      ? '已切换到「财务」角色：可以开具/作废结算发票'
      : '已切换到「销售」角色：结算发票只有财务能开，点开票会被拒绝'
  )
}
</script>

<template>
  <div class="app-container">
    <header class="app-header">
      <h1>线下沙龙场地智能推荐系统</h1>
      <p class="subtitle">多维度权重智能匹配 · 自动推荐最优场地</p>
      <div class="role-switch">
        <span class="role-label">当前角色：</span>
        <el-radio-group :model-value="currentRole" size="small" @change="changeRole">
          <el-radio-button value="SALES">销售</el-radio-button>
          <el-radio-button value="FINANCE">财务</el-radio-button>
        </el-radio-group>
        <el-tag v-if="currentRole === 'FINANCE'" type="success" size="small" effect="dark">
          财务在岗：可开票/作废
        </el-tag>
        <el-tag v-else type="warning" size="small" effect="dark">
          销售在岗：不能开票
        </el-tag>
      </div>
    </header>

    <el-tabs v-model="activeTab" type="border-card" class="app-tabs">
      <el-tab-pane label="需求提交" name="demand">
        <DemandSubmission />
      </el-tab-pane>
      <el-tab-pane label="场地管理" name="venue">
        <VenueManagement />
      </el-tab-pane>
      <el-tab-pane label="推荐结果" name="recommend">
        <RecommendResult />
      </el-tab-pane>
      <el-tab-pane label="开场值守" name="duty">
        <EventDayDuty />
      </el-tab-pane>
      <el-tab-pane label="历史记录" name="history">
        <HistoryRecords />
      </el-tab-pane>
      <el-tab-pane label="押金财务" name="finance">
        <FinanceLedger />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.app-container {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.app-header {
  text-align: center;
  color: #fff;
  margin-bottom: 20px;
  position: relative;
}

.app-header h1 {
  font-size: 32px;
  margin: 0 0 10px 0;
  font-weight: 600;
}

.app-header .subtitle {
  font-size: 16px;
  opacity: 0.9;
  margin: 0;
}

.role-switch {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.role-label {
  font-size: 14px;
  opacity: 0.9;
}

.app-tabs {
  max-width: 1200px;
  margin: 0 auto;
}

:deep(.el-tabs__header) {
  margin-bottom: 0;
}

:deep(.el-tabs__content) {
  padding: 20px;
  background: #fff;
  border-radius: 0 0 8px 8px;
}
</style>
