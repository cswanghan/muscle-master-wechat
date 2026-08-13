<script setup lang="ts">
import { onMounted, ref } from 'vue'

type Envelope<T> = {
  code: number
  message: string
  requestId?: string
  data: T
}

type StoreItem = {
  storeId: string
  name: string
  distanceM?: number | null
  near: boolean
  businessStart: string
  businessEnd: string
  open: boolean
}

type ProjectItem = {
  projectId: string
  name: string
  durationMinutes: number
  bufferMinutes: number
  priceFen: number
}

type TherapistItem = {
  therapistId: string
  name: string
  level: string
  ratingX100: number
  intro?: string
}

type LoginData = {
  token: string
  expiresIn: number
  customerId?: string
  staffId?: string
  typ?: string
  needPhone?: boolean
  name?: string
}

const stores = ref<StoreItem[]>([])
const projects = ref<ProjectItem[]>([])
const therapists = ref<TherapistItem[]>([])
const error = ref('')
const loading = ref(false)
const loginLoading = ref(false)
const loginPayload = ref<Envelope<LoginData> | null>(null)

function fenYuan(fen: number) {
  return (fen / 100).toFixed(0)
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(path)
  const body = (await res.json()) as Envelope<T>
  if (!res.ok || body.code !== 0) {
    throw new Error(body.message || `HTTP ${res.status}`)
  }
  return body.data
}

async function loadCatalog() {
  loading.value = true
  error.value = ''
  try {
    const [storePage, projectPage, therapistPage] = await Promise.all([
      getJson<{ items: StoreItem[] }>('/api/v1/c/stores'),
      getJson<{ items: ProjectItem[] }>('/api/v1/c/projects'),
      getJson<{ items: TherapistItem[] }>('/api/v1/c/therapists'),
    ])
    stores.value = storePage.items
    projects.value = projectPage.items
    therapists.value = therapistPage.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function devLogin() {
  loginLoading.value = true
  error.value = ''
  try {
    const res = await fetch('/api/v1/c/auth/wechat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'dev' }),
    })
    loginPayload.value = (await res.json()) as Envelope<LoginData>
    if (!res.ok || loginPayload.value.code !== 0) {
      throw new Error(loginPayload.value.message || `HTTP ${res.status}`)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loginLoading.value = false
  }
}

onMounted(loadCatalog)
</script>

<template>
  <div id="catalog-page" class="catalog">
    <el-card class="health-card" shadow="never">
      <template #header>
        <div class="card-head">
          <h1>C 端目录</h1>
          <el-button type="primary" :loading="loading" @click="loadCatalog">刷新</el-button>
        </div>
      </template>
      <el-alert
        v-if="error"
        title="目录加载失败"
        type="error"
        :description="error"
        show-icon
        :closable="false"
      />
      <p class="label">GET /api/v1/c/stores · 无需登录</p>
      <div id="store-list" class="store-list">
        <article v-for="s in stores" :key="s.storeId" class="store-card">
          <div class="store-title">{{ s.name }}</div>
          <div class="store-meta">
            营业 {{ s.businessStart }}–{{ s.businessEnd }}
            <el-tag :type="s.open ? 'success' : 'info'" size="small">{{ s.open ? '营业中' : '未开业' }}</el-tag>
          </div>
          <div class="store-id">{{ s.storeId }}</div>
        </article>
        <p v-if="!loading && stores.length === 0" class="label">暂无门店</p>
      </div>
    </el-card>

    <el-card class="health-card" shadow="never">
      <template #header>
        <h1>上架项目 / 技师</h1>
      </template>
      <div class="two-col">
        <ul class="plain">
          <li v-for="p in projects" :key="p.projectId">
            {{ p.name }} · {{ p.durationMinutes }}+{{ p.bufferMinutes }} 分 · ¥{{ fenYuan(p.priceFen) }}
          </li>
        </ul>
        <ul class="plain">
          <li v-for="t in therapists" :key="t.therapistId">
            {{ t.name }} · {{ t.level }} · {{ (t.ratingX100 / 100).toFixed(1) }}
          </li>
        </ul>
      </div>
    </el-card>

    <el-card id="login-card" class="health-card" shadow="never">
      <template #header>
        <div class="card-head">
          <h1>Dev 微信登录</h1>
          <el-button id="dev-login-btn" type="primary" :loading="loginLoading" @click="devLogin">
            POST /c/auth/wechat code=dev
          </el-button>
        </div>
      </template>
      <p class="label">JWT HS256 · typ=C · expiresIn=7200</p>
      <pre v-if="loginPayload" id="login-token" class="json">{{ JSON.stringify(loginPayload, null, 2) }}</pre>
    </el-card>
  </div>
</template>
