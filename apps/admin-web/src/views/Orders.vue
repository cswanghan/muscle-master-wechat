<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getToken, request } from '../api'

type OrderItem = {
  orderId: string
  orderNo: string
  storeId: string
  therapistId: string
  status: string
  serviceDate: string
  createdAt: string
  payableFen: number
  highlight: boolean
}

type OrderPage = {
  items: OrderItem[]
  nextCursor?: string | null
  view: string
}

type StoreItem = { storeId: string; name: string }
type TherapistItem = { therapistId: string; name: string }

const STATUS_CHIPS = [
  { key: 'all', label: '全部', view: 'all' as const, status: '', id: 'order-view-all' },
  { key: 'PENDING_PAY', label: '待支付', view: 'all' as const, status: 'PENDING_PAY' },
  { key: 'BOOKED', label: '已预约', view: 'all' as const, status: 'BOOKED' },
  { key: 'IN_SERVICE', label: '服务中', view: 'all' as const, status: 'IN_SERVICE' },
  { key: 'NO_SHOW', label: '爽约', view: 'all' as const, status: 'NO_SHOW' },
  { key: 'abnormal', label: '异常单', view: 'abnormal_first' as const, status: '', id: 'order-view-abnormal' },
]

const STATUS_LABEL: Record<string, string> = {
  PENDING_PAY: '待支付',
  BOOKED: '待到店',
  CHECKED_IN: '已到店',
  IN_SERVICE: '服务中',
  COMPLETED: '已完成',
  ABNORMAL: '异常单',
  CANCELLED: '已取消',
  CLOSED: '已关闭',
  NO_SHOW: '爽约',
}

const view = ref<'abnormal_first' | 'all'>('all')
const statusFilter = ref('')
const chip = ref('all')
const q = ref('')
const orders = ref<OrderItem[]>([])
const stores = ref<StoreItem[]>([])
const therapists = ref<TherapistItem[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const counts = ref<Record<string, number>>({})

const storeName = computed(() => {
  const m: Record<string, string> = {}
  stores.value.forEach((s) => {
    m[s.storeId] = s.name
  })
  return m
})
const therapistName = computed(() => {
  const m: Record<string, string> = {}
  therapists.value.forEach((t) => {
    m[t.therapistId] = t.name
  })
  return m
})

const shown = computed(() => {
  const needle = q.value.trim()
  if (!needle) return orders.value
  return orders.value.filter((o) =>
    (o.orderNo || '').includes(needle) || (o.orderId || '').includes(needle),
  )
})

function fenYuan(fen: number) {
  return (fen / 100).toFixed(2)
}

function shortNo(orderNo: string) {
  if (!orderNo) return '—'
  if (orderNo.length <= 12) return orderNo
  return orderNo.slice(0, 3) + '…' + orderNo.slice(-6)
}

function rowClass({ row }: { row: OrderItem }) {
  return row.highlight || row.status === 'ABNORMAL' ? 'abnormal-row' : ''
}

function statusClass(status: string) {
  if (status === 'ABNORMAL') return 'alert'
  if (status === 'PENDING_PAY') return 'warn'
  if (status === 'IN_SERVICE') return 'ink'
  if (status === 'NO_SHOW' || status === 'CLOSED' || status === 'CANCELLED') return ''
  return 'ok'
}

async function loadMeta() {
  const [s, t] = await Promise.all([
    request<{ items: StoreItem[] }>('/api/v1/a/stores').catch(() => ({ items: [] })),
    request<{ items: TherapistItem[] }>('/api/v1/a/therapists').catch(() => ({ items: [] })),
  ])
  stores.value = s.items || []
  therapists.value = t.items || []
}

async function loadCounts() {
  try {
    const all = await request<OrderPage>('/api/v1/a/orders?view=all&limit=100')
    const items = all.items || []
    const next: Record<string, number> = { all: items.length, abnormal: 0 }
    items.forEach((o) => {
      next[o.status] = (next[o.status] || 0) + 1
      if (o.highlight || o.status === 'ABNORMAL') next.abnormal += 1
    })
    counts.value = next
  } catch {
    counts.value = {}
  }
}

async function loadOrders(reset = true) {
  if (!getToken()) {
    error.value = '请先登录超管'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ view: view.value, limit: '20' })
    if (view.value === 'all' && statusFilter.value) {
      params.set('status', statusFilter.value)
    }
    if (!reset && nextCursor.value && view.value === 'all') {
      params.set('cursor', nextCursor.value)
    }
    const data = await request<OrderPage>(`/api/v1/a/orders?${params.toString()}`)
    orders.value = reset ? data.items : orders.value.concat(data.items)
    nextCursor.value = data.nextCursor ?? null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function switchChip(next: (typeof STATUS_CHIPS)[number]) {
  chip.value = next.key
  view.value = next.view
  statusFilter.value = next.status
  nextCursor.value = null
  void loadOrders(true)
}

onMounted(async () => {
  await loadMeta()
  await Promise.all([loadOrders(true), loadCounts()])
})
</script>

<template>
  <div id="orders-page" class="orders-page">
    <header class="dash-head">
      <h1>订单中心</h1>
      <div class="dash-tools">
        <el-input
          v-model="q"
          placeholder="手机号 / 订单号"
          clearable
          style="width: 200px"
        />
        <el-button type="primary" :loading="loading" @click="loadOrders(true)">刷新</el-button>
      </div>
    </header>
    <el-alert
      v-if="error"
      title="订单加载失败"
      type="error"
      :description="error"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />
    <div class="chip-row">
      <button
        v-for="c in STATUS_CHIPS"
        :id="c.id"
        :key="c.key"
        class="filter-chip"
        :class="{ on: chip === c.key, alert: c.key === 'abnormal' }"
        type="button"
        @click="switchChip(c)"
      >
        {{ c.label }}
        <em v-if="counts[c.key] != null || counts[c.status]">
          {{ counts[c.key] ?? counts[c.status] }}
        </em>
      </button>
    </div>
    <div class="panel table-wrap">
      <table id="order-table" class="order-table">
        <thead>
          <tr>
            <th>订单号 / 门店</th>
            <th>客户</th>
            <th>项目 · 技师</th>
            <th>到店时间</th>
            <th>实收</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in shown" :key="row.orderId" :class="rowClass({ row })">
            <td>
              <div class="mono">{{ shortNo(row.orderNo) }}</div>
              <div class="sub">{{ storeName[row.storeId] || '门店' }}</div>
            </td>
            <td class="mute">—</td>
            <td>
              <div>到店项目</div>
              <div class="sub">{{ therapistName[row.therapistId] || '技师' }}</div>
              <div v-if="row.highlight" class="alert-line">已支付但需人工重排</div>
            </td>
            <td class="mono">{{ row.serviceDate }}</td>
            <td class="mono">{{ fenYuan(row.payableFen) }}</td>
            <td>
              <span class="status-pill" :class="statusClass(row.status)">
                {{ STATUS_LABEL[row.status] || row.status }}
              </span>
            </td>
            <td>
              <router-link class="link" to="/frontdesk">
                {{ row.highlight || row.status === 'ABNORMAL' ? '干预' : '详情' }}
              </router-link>
            </td>
          </tr>
          <tr v-if="!shown.length && !loading">
            <td colspan="7" class="label" style="padding: 24px">暂无订单</td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <span class="label">游标分页，不支持跳页；导出走异步任务</span>
        <div>
          <el-button disabled>上一页</el-button>
          <el-button
            v-if="view === 'all' && nextCursor"
            id="order-more"
            type="primary"
            plain
            @click="loadOrders(false)"
          >
            下一页
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>
