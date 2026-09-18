<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { venueApi, resolveError, type Venue } from '../api'

const activityCategories = ['会议培训', '产品发布', '团建活动', '学术研讨', '文艺演出', '展览展示', '婚礼庆典', '生日派对']
const facilityOptions = ['投影仪', '音响系统', '麦克风', '白板', 'WiFi', '空调', '餐饮服务', '停车位', '舞台灯光', '直播设备']

const venues = ref<Venue[]>([])
const showModal = ref(false)
const isEditing = ref(false)
const selectedFacilities = ref<string[]>([])
const selectedActivityTypes = ref<string[]>([])

const venueForm = reactive<Venue>({
  name: '',
  capacity: 0,
  pricePerDay: 0,
  facilities: '',
  activityTypes: '',
  description: '',
  status: 1,
  version: 0
})

const loadVenues = async () => {
  const res = await venueApi.getAll()
  venues.value = res.data
}

const openModal = (venue?: Venue) => {
  if (venue) {
    isEditing.value = true
    Object.assign(venueForm, venue)
    selectedFacilities.value = venue.facilities ? venue.facilities.split(',').filter(Boolean) : []
    selectedActivityTypes.value = venue.activityTypes ? venue.activityTypes.split(',').filter(Boolean) : []
  } else {
    isEditing.value = false
    Object.assign(venueForm, {
      name: '',
      capacity: 0,
      pricePerDay: 0,
      facilities: '',
      activityTypes: '',
      description: '',
      status: 1,
      version: 0
    })
    selectedFacilities.value = []
    selectedActivityTypes.value = []
  }
  showModal.value = true
}

const saveVenue = async () => {
  if (!venueForm.name || !venueForm.capacity || !venueForm.pricePerDay) {
    ElMessage.warning('请填写必填项')
    return
  }

  venueForm.facilities = selectedFacilities.value.join(',')
  venueForm.activityTypes = selectedActivityTypes.value.join(',')

  try {
    if (isEditing.value) {
      const res = await venueApi.update(venueForm.id!, venueForm)
      ElMessage.success('场地信息更新成功')
      if (res.data.breakMessage) {
        ElMessageBox.alert(res.data.breakMessage, '已有锁定因此破裂', {
          type: 'warning',
          confirmButtonText: '知道了'
        })
      }
      showModal.value = false
      loadVenues()
    } else {
      await venueApi.create(venueForm)
      ElMessage.success('场地添加成功')
      showModal.value = false
      loadVenues()
    }
  } catch (error: any) {
    // 两人前后脚改名：后端只留下最后一次对外名，失败方看到名称已被更新
    if (error?.response?.status === 409) {
      showModal.value = false
      await ElMessageBox.alert(resolveError(error), '场地信息已被更新', {
        type: 'warning',
        confirmButtonText: '知道了'
      })
      loadVenues()
    } else {
      ElMessage.error(resolveError(error))
    }
  }
}

const deleteVenue = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要停用该场地吗？停用后引用该场地的有效锁定会自行破裂。', '提示', { type: 'warning' })
    const res = await venueApi.delete(id)
    ElMessage.success('场地已停用')
    if (res.data.breakMessage) {
      ElMessageBox.alert(res.data.breakMessage, '已有锁定因此破裂', {
        type: 'warning',
        confirmButtonText: '知道了'
      })
    }
    loadVenues()
  } catch {}
}

onMounted(loadVenues)
</script>

<template>
  <div class="venue-container">
    <div class="header">
      <h3>场地管理</h3>
      <el-button type="primary" @click="openModal">添加场地</el-button>
    </div>
    
    <el-table :data="venues" border class="venue-table">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="场地名称" />
      <el-table-column prop="capacity" label="容纳人数" width="100" />
      <el-table-column prop="pricePerDay" label="单日定价" width="120">
        <template #default="scope">
          ¥{{ scope.row.pricePerDay }}
        </template>
      </el-table-column>
      <el-table-column prop="facilities" label="配套设施" min-width="150">
        <template #default="scope">
          <el-tag v-for="facility in (scope.row.facilities?.split(',') || [])" :key="facility" size="small">{{ facility }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="activityTypes" label="适配活动" min-width="150">
        <template #default="scope">
          <el-tag v-for="type in (scope.row.activityTypes?.split(',') || [])" :key="type" size="small" type="success">{{ type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="150">
        <template #default="scope">
          <el-button size="small" @click="openModal(scope.row)">编辑</el-button>
          <el-button size="small" type="danger" @click="deleteVenue(scope.row.id!)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    
    <el-dialog :title="isEditing ? '编辑场地' : '添加场地'" v-model="showModal">
      <el-form :model="venueForm" label-width="100px">
        <el-form-item label="场地名称" required>
          <el-input v-model="venueForm.name" />
        </el-form-item>
        <el-form-item label="容纳人数" required>
          <el-input-number v-model="venueForm.capacity" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item label="单日定价" required>
          <el-input-number v-model="venueForm.pricePerDay" :min="0" />
        </el-form-item>
        <el-form-item label="配套设施">
          <el-select v-model="selectedFacilities" multiple placeholder="请选择配套设施">
            <el-option v-for="facility in facilityOptions" :key="facility" :label="facility" :value="facility" />
          </el-select>
        </el-form-item>
        <el-form-item label="适配活动类型">
          <el-select v-model="selectedActivityTypes" multiple placeholder="请选择活动类型">
            <el-option v-for="cat in activityCategories" :key="cat" :label="cat" :value="cat" />
          </el-select>
        </el-form-item>
        <el-form-item label="场地描述">
          <el-input v-model="venueForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showModal = false">取消</el-button>
        <el-button type="primary" @click="saveVenue">{{ isEditing ? '确定修改' : '确定添加' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.venue-container {
  max-width: 100%;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header h3 {
  font-size: 18px;
  margin: 0;
}

.venue-table {
  width: 100%;
}
</style>
