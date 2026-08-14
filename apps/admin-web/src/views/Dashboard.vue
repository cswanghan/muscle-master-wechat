<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getToken, request } from '../api'

type Util = {
  storeId: string
  date: string
  rateX10000: number | null
  byHour: { hour: number; rateX10000: number | null }[]
}

type Task = {
  id: string
  taskType: string
  title: string
  status: string
}

type OrderItem = {
  orderId: string
  status: string
  payableFen: number
  highlight: boolean
}

type StoreItem = { storeId: string; name: string }

const util = ref<Util | null>(null)
const tasks = ref<Task[]>([])
const orders = ref<OrderItem[]>([])
const stores = ref<StoreItem[]>([])
const error = ref('')

function pct(rate: number | null | undefined) {
  if (rate == null) return '—'
  return (rate / 100).toFixed(1) + '%'
}

function yuan(fen: number) {
  return '¥' + (fen / 100).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

const rate = computed(() => util.value?.rateX10000 ?? null)
const hours = computed(() => util.value?.byHour ?? [])
const maxHour = computed(() =>
  Math.max(1, ...hours.value.map((h) => h.rateX10000 ?? 0)),
)
const orderCount = computed(() => orders.value.length)
const avgFen = computed(() => {
  if (!orders.value.length) return 0
  return Math.round(orders.value.reduce((s, o) => s + o.payableFen, 0) / orders.value.length)
})
const refundTasks = computed(() => tasks.value.filter((t) => t.taskType.includes('REFUND')))
const leaveTasks = computed(() => tasks.value.filter((t) => t.taskType.includes('LEAVE')))
const abnormalTasks = computed(() =>
  tasks.value.filter((t) => t.taskType.includes('ABNORMAL') || t.taskType.includes('MANUAL')),
)
const idleHours = computed(() =>
  hours.value.filter((h) => h.hour >= 13 && h.hour <= 16 && (h.rateX10000 ?? 0) < 5000),
)
const storeRank = computed(() => {
  const name = stores.value.find((s) => s.storeId === util.value?.storeId)?.name || '本店'
  const p = (rate.value ?? 0) / 100
  return [{
    name,
    text: pct(rate.value),
    width: Math.max(8, Math.min(100, Math.round(p))),
    tone: p >= 85 ? 'full' : p >= 70 ? 'ok' : p >= 50 ? 'idle' : 'alert',
  }]
})

function hourTone(rateX: number | null) {
  if (rateX == null) return 'rest'
  const p = rateX / 100
  if (p < 50) return 'idle'
  if (p < 85) return 'ok'
  return 'full'
}

function hourLabelClass(hour: number) {
  return idleHours.value.some((h) => h.hour === hour) ? 'idle' : ''
}

async function load() {
  if (!getToken()) {
    error.value = '请先登录'
    return
  }
  error.value = ''
  try {
    const today = new Date().toISOString().slice(0, 10)
    const [o, s] = await Promise.all([
      request<{ items: OrderItem[] }>('/api/v1/a/orders?view=all&limit=50').catch(() => ({ items: [] })),
      request<{ items: StoreItem[] }>('/api/v1/a/stores').catch(() => ({ items: [] })),
    ])
    orders.value = o.items || []
    stores.value = s.items || []
    const sid = stores.value[0]?.storeId || ''
    const utilQs = new URLSearchParams({ date: today })
    if (sid) utilQs.set('storeId', sid)
    const [u, t] = await Promise.all([
      request<Util>(`/api/v1/f/metrics/utilization?${utilQs}`).catch(() => null),
      request<{ items: Task[] }>(`/api/v1/f/human-tasks?status=OPEN${sid ? `&storeId=${sid}` : ''}`).catch(() => ({ items: [] })),
    ])
    util.value = u
    tasks.value = t.items || []
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(load)
</script>

<template>
  <div class="dash">
    <header class="dash-head">
      <h1>数据看板</h1>
      <div class="dash-tools">
        <span class="pill">全部门店</span>
        <span class="pill">今日 · 实时</span>
        <el-button type="primary" @click="load">刷新</el-button>
      </div>
    </header>
    <p v-if="error" class="label">{{ error }}</p>

    <div class="kpi-row five">
      <div class="kpi jade">
        <div class="kpi-cap">满班率</div>
        <div class="kpi-num">{{ pct(rate) }}</div>
        <div class="kpi-sub">目标 78%</div>
      </div>
      <div class="kpi">
        <div class="kpi-cap">订单量</div>
        <div class="kpi-num">{{ orderCount.toLocaleString() }}</div>
        <div class="kpi-sub">本页已加载</div>
      </div>
      <div class="kpi">
        <div class="kpi-cap">客单价</div>
        <div class="kpi-num copper">{{ yuan(avgFen) }}</div>
        <div class="kpi-sub">应付均价</div>
      </div>
      <div class="kpi">
        <div class="kpi-cap">30 日复购率</div>
        <div class="kpi-num">—</div>
        <div class="kpi-sub">P0 未开通会员复购</div>
      </div>
      <div class="kpi">
        <div class="kpi-cap">储值余额池</div>
        <div class="kpi-num">¥0</div>
        <div class="kpi-sub">P0 未开通储值</div>
      </div>
    </div>

    <div class="dash-grid">
      <section class="panel">
        <div class="panel-head">
          <h2>分时满班率 · 今日</h2>
          <span class="label">低谷用暖铜标出</span>
        </div>
        <div class="bars">
          <div v-for="h in hours" :key="h.hour" class="bar">
            <div class="bar-track">
              <div
                class="bar-fill"
                :class="hourTone(h.rateX10000)"
                :style="{ height: `${Math.max(8, ((h.rateX10000 ?? 0) / maxHour) * 100)}%` }"
              />
            </div>
            <div class="bar-h" :class="hourLabelClass(h.hour)">{{ h.hour }}</div>
          </div>
          <div v-if="!hours.length" class="label">登录后加载今日排班</div>
        </div>
        <div v-if="idleHours.length" class="dash-note">
          <span>午后低谷 {{ idleHours.length }} 小时，建议开午间特惠时段规则。</span>
          <router-link class="note-btn" to="/catalog">去配置</router-link>
        </div>
      </section>
      <div class="dash-side">
        <section class="panel">
          <div class="panel-head">
            <h2>门店满班率排行</h2>
            <span class="label">{{ stores.length || 1 }} 店</span>
          </div>
          <div v-for="(s, i) in storeRank" :key="s.name" class="rank">
            <span class="rank-i">{{ i + 1 }}</span>
            <span class="rank-n">{{ s.name }}</span>
            <span class="rank-bar"><i :class="s.tone" :style="{ width: s.width + '%' }" /></span>
            <span class="rank-p">{{ s.text }}</span>
          </div>
          <p v-if="!storeRank.length" class="label">登录后加载门店</p>
        </section>
        <section class="panel">
          <div class="panel-head">
            <h2>待我处理</h2>
          </div>
          <router-link class="todo" to="/orders">
            <span class="todo-n alert">{{ abnormalTasks.length }}</span>
            <span>异常单人工干预</span>
          </router-link>
          <router-link class="todo" to="/schedule">
            <span class="todo-n warn">{{ leaveTasks.length }}</span>
            <span>技师请假申请</span>
          </router-link>
          <router-link class="todo" to="/orders">
            <span class="todo-n">{{ refundTasks.length }}</span>
            <span>退款审批（满 ¥500）</span>
          </router-link>
        </section>
      </div>
    </div>
  </div>
</template>
