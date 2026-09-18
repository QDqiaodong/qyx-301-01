<script setup lang="ts">
import { ref } from 'vue'
import VenueManagement from './components/VenueManagement.vue'
import DemandSubmission from './components/DemandSubmission.vue'
import RecommendResult from './components/RecommendResult.vue'
import EventDayDuty from './components/EventDayDuty.vue'
import HistoryRecords from './components/HistoryRecords.vue'
import FinanceLedger from './components/FinanceLedger.vue'

const activeTab = ref('demand')
</script>

<template>
  <div class="app-container">
    <header class="app-header">
      <h1>线下沙龙场地智能推荐系统</h1>
      <p class="subtitle">多维度权重智能匹配 · 自动推荐最优场地</p>
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
