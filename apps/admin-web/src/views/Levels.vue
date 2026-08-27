<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { request } from '../api'

type PendingItem = {
  logId: string
  therapistId: string
  therapistName: string
  fromLevel: string
  toLevel: string
  toLevelName: string
  reviewCount: number
  positiveRateX100: number
  createdAt: string
}

const items = ref<PendingItem[]>([])
const loading = ref(false)
const error = ref('')

const LEVEL_NAME: Record<string, string> = {
  JUNIOR: '初级', MIDDLE: '中级', SENIOR: '资深', CHIEF: '首席',
}

function levelName(code: string) {
  return LEVEL_NAME[code] ?? code ?? '—'
}

function rate(x100: number) {
  return (x100 / 100).toFixed(1) + '%'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await request<{ items: PendingItem[] }>('/api/v1/a/therapist-levels/pending')
    items.value = data.items ?? []
  } catch (e) {
    error.value = (e as Error).message || '加载失败'
  } finally {
    loading.value = false
  }
}

// 扫描只提名，不改等级 —— 重复点不会重复堆条目，同一技师同一目标档只留一条待确认。
async function scan() {
  loading.value = true
  try {
    const data = await request<{ scanned: number; proposed: number }>(
      '/api/v1/a/therapist-levels/scan', { method: 'POST' })
    ElMessage.success(`扫描 ${data.scanned} 位技师，新增 ${data.proposed} 条待确认`)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '扫描失败')
  } finally {
    loading.value = false
  }
}

async function decide(row: PendingItem, approve: boolean) {
  const verb = approve ? '确认晋升' : '驳回'
  await ElMessageBox.confirm(
    `${row.therapistName}：${levelName(row.fromLevel)} → ${row.toLevelName}，${verb}？`
      + (approve ? '\n确认后立即生效，等级只影响展示，不改价格。' : ''),
    verb,
    { type: approve ? 'success' : 'warning' },
  )
  try {
    await request(`/api/v1/a/therapist-levels/${row.logId}/${approve ? 'confirm' : 'reject'}`,
      { method: 'POST', body: JSON.stringify({ requestId: 'lv-' + Date.now() }) })
    ElMessage.success(verb + '成功')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || (verb + '失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="levels">
    <header class="dash-head">
      <div>
        <h1>等级待确认</h1>
        <p class="label">
          系统按累计评价数与好评率算达标，只提名不生效；确认后改技师等级，当前仅影响展示，不动价格。
        </p>
      </div>
      <div class="acts">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button id="level-scan-btn" type="primary" :loading="loading" @click="scan">立即扫描</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-table id="level-pending-table" :data="items" stripe v-loading="loading">
      <el-table-column prop="therapistName" label="技师" width="120" />
      <el-table-column label="晋升" width="160">
        <template #default="{ row }">
          {{ levelName(row.fromLevel) }} → <strong>{{ row.toLevelName }}</strong>
        </template>
      </el-table-column>
      <el-table-column prop="reviewCount" label="累计评价" width="110" />
      <el-table-column label="累计好评率" width="120">
        <template #default="{ row }">{{ rate(row.positiveRateX100) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="达标时间" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="decide(row, true)">确认</el-button>
          <el-button size="small" @click="decide(row, false)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="!loading && !items.length" class="empty">暂无待确认的晋升</p>
  </div>
</template>

<style scoped>
.levels { padding: 24px; }
.dash-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.label { color: #8b948f; font-size: 13px; margin: 4px 0 0; max-width: 640px; line-height: 1.6; }
.acts { display: flex; gap: 8px; }
.empty { color: #8b948f; text-align: center; padding: 32px 0; }
</style>
