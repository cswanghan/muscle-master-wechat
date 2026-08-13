<script setup lang="ts">
import { onMounted, ref } from 'vue'
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

const view = ref<'abnormal_first' | 'all'>('abnormal_first')
const orders = ref<OrderItem[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')

function fenYuan(fen: number) {
  return (fen / 100).toFixed(0)
}

function rowClass({ row }: { row: OrderItem }) {
  return row.highlight ? 'abnormal-row' : ''
}

function statusType(status: string) {
  if (status === 'ABNORMAL') return 'danger'
  if (status === 'PENDING_PAY') return 'warning'
  if (status === 'COMPLETED') return 'success'
  return 'info'
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

function switchView(next: 'abnormal_first' | 'all') {
  view.value = next
  nextCursor.value = null
  void loadOrders(true)
}

onMounted(() => loadOrders(true))
</script>

<template>
  <div id="orders-page" class="catalog">
    <el-card class="health-card" shadow="never">
      <template #header>
        <div class="card-head">
          <h1>订单中心</h1>
          <el-button type="primary" :loading="loading" @click="loadOrders(true)">刷新</el-button>
        </div>
      </template>
      <el-alert
        v-if="error"
        title="订单加载失败"
        type="error"
        :description="error"
        show-icon
        :closable="false"
      />
      <p class="label">GET /a/orders · view={{ view }} · 异常行高亮 · all 才有游标</p>
      <div class="toolbar">
        <el-radio-group :model-value="view" @change="(v: string) => switchView(v as 'abnormal_first' | 'all')">
          <el-radio-button id="order-view-abnormal" value="abnormal_first">异常优先</el-radio-button>
          <el-radio-button id="order-view-all" value="all">全部</el-radio-button>
        </el-radio-group>
        <el-tag v-if="view === 'abnormal_first'" type="danger" effect="plain">非游标 · highlight 恒 true</el-tag>
        <el-tag v-else type="info" effect="plain">游标 created_at,id</el-tag>
      </div>
      <el-table
        id="order-table"
        :data="orders"
        :row-class-name="rowClass"
        row-key="orderId"
      >
        <el-table-column prop="orderNo" label="单号" width="150" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
            <el-tag v-if="row.highlight" type="danger" size="small" class="hl-tag">异常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="serviceDate" label="服务日" width="120" />
        <el-table-column prop="createdAt" label="创建" width="180" />
        <el-table-column label="应付" width="90">
          <template #default="{ row }">¥{{ fenYuan(row.payableFen) }}</template>
        </el-table-column>
        <el-table-column prop="storeId" label="门店" show-overflow-tooltip />
      </el-table>
      <div v-if="view === 'all' && nextCursor" class="toolbar">
        <el-button id="order-more" @click="loadOrders(false)">下一页</el-button>
      </div>
    </el-card>
  </div>
</template>
