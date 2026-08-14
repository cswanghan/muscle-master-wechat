<script setup lang="ts">
import { onMounted, ref } from 'vue'

type HealthPayload = {
  status?: string
  [key: string]: unknown
}

const loading = ref(true)
const error = ref('')
const payload = ref<HealthPayload | null>(null)

async function loadHealth() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch('/actuator/health')
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }
    payload.value = (await res.json()) as HealthPayload
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    payload.value = null
  } finally {
    loading.value = false
  }
}

onMounted(loadHealth)
</script>

<template>
  <el-card class="health-card" shadow="never">
    <template #header>
      <div class="card-head">
        <h1>系统健康</h1>
        <el-button type="primary" :loading="loading" @click="loadHealth">刷新</el-button>
      </div>
    </template>
    <el-alert
      v-if="error"
      title="无法读取 /actuator/health"
      type="error"
      :description="error"
      show-icon
      :closable="false"
    />
    <div v-else class="status-row">
      <span class="label">服务状态</span>
      <el-tag
        id="health-status"
        :type="payload?.status === 'UP' ? 'success' : 'danger'"
        effect="dark"
        size="large"
      >
        {{ loading ? '…' : payload?.status ?? 'UNKNOWN' }}
      </el-tag>
    </div>
    <pre v-if="payload" class="json">{{ JSON.stringify(payload, null, 2) }}</pre>
  </el-card>
</template>
