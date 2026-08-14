<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getToken, request } from '../api'

type Block = { slotNo: number; start: string; state: string }
type TherapistAvail = {
  therapistId: string
  name: string
  level?: string
  blocks?: Block[]
  starts?: { slotNo: number; priceFen: number }[]
}
type OrderItem = {
  orderId: string
  orderNo: string
  therapistId: string
  status: string
  serviceDate: string
  payableFen: number
  highlight: boolean
}
type StoreItem = { storeId: string; name: string }
type ProjectItem = { projectId: string; name: string }

const HOURS = [10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21]
const date = ref(new Date().toISOString().slice(0, 10))
const storeId = ref('')
const projectId = ref('')
const stores = ref<StoreItem[]>([])
const projects = ref<ProjectItem[]>([])
const therapists = ref<TherapistAvail[]>([])
const orders = ref<OrderItem[]>([])
const selectedOrder = ref<OrderItem | null>(null)
const selectedTherapist = ref<TherapistAvail | null>(null)
const viewMode = ref<'therapist' | 'bed'>('therapist')
const error = ref('')

const PX = 64
const STATUS_LABEL: Record<string, string> = {
  PENDING_PAY: '待支付',
  BOOKED: '待到店',
  CHECKED_IN: '已到店',
  IN_SERVICE: '服务中',
  COMPLETED: '已完成',
  ABNORMAL: '异常单',
  CANCELLED: '已取消',
  CLOSED: '已关闭',
}

const LEVEL_LABEL: Record<string, string> = {
  SENIOR: '资深',
  MIDDLE: '中级',
  JUNIOR: '初级',
}

function leftOf(slot: number) {
  return ((slot - 40) / 4) * PX
}

function widthOf(state: string, i: number, blocks: Block[]) {
  let n = 1
  while (i + n < blocks.length && blocks[i + n].state === state && blocks[i + n].slotNo === blocks[i].slotNo + n) {
    n += 1
  }
  return (n / 4) * PX
}

function groups(blocks: Block[]) {
  const out: { start: Block; width: number; state: string; slots: number }[] = []
  let i = 0
  while (i < blocks.length) {
    const b = blocks[i]
    if (b.slotNo < 40 || b.slotNo >= 88) {
      i += 1
      continue
    }
    const w = widthOf(b.state, i, blocks)
    const slots = Math.round((w / PX) * 4)
    out.push({ start: b, width: w, state: b.state, slots })
    i += Math.max(1, slots)
  }
  return out
}

function tone(state: string) {
  if (state === 'BOOKED' || state === 'BUFFER') return 'booked'
  if (state === 'LOCKED') return 'locked'
  if (state === 'REST') return 'rest'
  return 'free'
}

function blockLabel(g: { start: Block; state: string; slots: number }) {
  if (g.state === 'BOOKED') return `${g.start.start} 已约 ${g.slots * 15}'`
  if (g.state === 'LOCKED') return `${g.start.start} 锁`
  if (g.state === 'REST') return '休息 / 请假'
  if (g.state === 'BUFFER') return '缓冲'
  if (g.state === 'FREE' && g.slots >= 4) return `空档 ${g.slots * 15}′`
  return ''
}

function yuanSafe(fen: number) {
  return '¥' + (fen / 100).toFixed(2)
}

const todayOrders = computed(() =>
  orders.value.filter((o) => o.serviceDate === date.value),
)

const selectedOrders = computed(() => {
  const tid = selectedTherapist.value?.therapistId
  if (!tid) return todayOrders.value
  return todayOrders.value.filter((o) => o.therapistId === tid)
})

const weekday = computed(() => {
  const d = new Date(date.value + 'T00:00:00')
  return '周' + '日一二三四五六'[d.getDay()]
})

const bedHint = computed(() => {
  const booked = therapists.value.reduce((n, t) => {
    return n + (t.blocks || []).filter((b) => b.state === 'BOOKED' || b.state === 'BUFFER').length
  }, 0)
  return `${Math.ceil(booked / 4)} 段占用 · 对照技师档期`
})

async function load() {
  if (!getToken()) {
    error.value = '请先登录'
    return
  }
  error.value = ''
  try {
    if (!stores.value.length) {
      const [s, p] = await Promise.all([
        request<{ items: StoreItem[] }>('/api/v1/a/stores'),
        request<{ items: ProjectItem[] }>('/api/v1/a/projects'),
      ])
      stores.value = s.items || []
      projects.value = p.items || []
      if (!storeId.value && stores.value[0]) storeId.value = stores.value[0].storeId
      if (!projectId.value && projects.value[0]) projectId.value = projects.value[0].projectId
    }
    if (!storeId.value || !projectId.value) return
    const [avail, ords] = await Promise.all([
      request<{ therapists: TherapistAvail[] }>(
        `/api/v1/c/availability?storeId=${storeId.value}&date=${date.value}&projectId=${projectId.value}&includeBusy=1`,
      ),
      request<{ items: OrderItem[] }>('/api/v1/a/orders?view=all&limit=50'),
    ])
    therapists.value = avail.therapists || []
    orders.value = ords.items || []
    selectedTherapist.value = therapists.value[0] ?? null
    selectedOrder.value = todayOrders.value[0] ?? null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function pickTherapist(t: TherapistAvail) {
  selectedTherapist.value = t
  selectedOrder.value = todayOrders.value.find((o) => o.therapistId === t.therapistId) ?? selectedOrder.value
}

onMounted(load)
</script>

<template>
  <div class="sched">
    <header class="dash-head">
      <h1>排班中心</h1>
      <div class="dash-tools">
        <el-select v-model="storeId" style="width: 160px" @change="load">
          <el-option v-for="s in stores" :key="s.storeId" :label="s.name" :value="s.storeId" />
        </el-select>
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" @change="load" />
        <span class="pill">{{ weekday }}</span>
        <div class="seg">
          <button type="button" :class="{ on: viewMode === 'therapist' }" @click="viewMode = 'therapist'">技师视图</button>
          <button type="button" :class="{ on: viewMode === 'bed' }" @click="viewMode = 'bed'">床位视图</button>
        </div>
        <span class="legend-dot booked" />已预约
        <span class="legend-dot locked" />锁定中
        <span class="legend-dot rest" />休息/请假
        <router-link class="el-button el-button--primary" to="/frontdesk">散客开单</router-link>
      </div>
    </header>
    <p v-if="error" class="label">{{ error }}</p>

    <div class="gantt-wrap">
      <div class="gantt">
        <div v-if="viewMode === 'bed'" class="label" style="padding: 24px">
          床位视图对照技师档期：P0 按技师×床双锁，拖拽改约在前台收银完成。
        </div>
        <template v-else>
          <div class="gantt-head">
            <div class="gantt-name">技师</div>
            <div class="gantt-hours">
              <span v-for="h in HOURS" :key="h" class="gantt-h">{{ h }}:00</span>
            </div>
          </div>
          <div
            v-for="t in therapists"
            :key="t.therapistId"
            class="gantt-row"
            :class="{ on: selectedTherapist?.therapistId === t.therapistId }"
            @click="pickTherapist(t)"
          >
            <div class="gantt-name">
              <strong>{{ t.name }}</strong>
              <small>{{ LEVEL_LABEL[t.level || ''] || t.level || '技师' }}</small>
            </div>
            <div class="gantt-track">
              <div
                v-for="(g, i) in groups(t.blocks || [])"
                :key="i"
                class="gantt-block"
                :class="tone(g.state)"
                :style="{ left: leftOf(g.start.slotNo) + 'px', width: g.width + 'px' }"
              >
                {{ blockLabel(g) }}
              </div>
            </div>
          </div>
          <div class="gantt-foot">
            <div class="gantt-name">床位占用</div>
            <div class="gantt-track bed-occ">
              <span>{{ bedHint }}</span>
            </div>
          </div>
          <div v-if="!therapists.length" class="label" style="padding: 24px">登录后加载今日档期</div>
        </template>
      </div>

      <aside class="detail">
        <h2>订单详情</h2>
        <article v-if="selectedOrder" class="detail-card" :class="{ alert: selectedOrder.highlight }">
          <div class="detail-top">
            <strong>到店项目</strong>
            <span class="status-pill">{{ STATUS_LABEL[selectedOrder.status] || selectedOrder.status }}</span>
          </div>
          <div class="mono sub">{{ selectedOrder.orderNo }}</div>
          <div class="kv"><span>技师</span><b>{{ selectedTherapist?.name || '—' }}</b></div>
          <div class="kv"><span>服务日</span><b>{{ selectedOrder.serviceDate }}</b></div>
          <div class="kv"><span>实收</span><b class="copper">{{ yuanSafe(selectedOrder.payableFen) }}</b></div>
        </article>
        <p v-else class="label">点左侧技师查看当日订单</p>
        <p class="hint">拖动卡片可改约或换技师，系统会同时校验技师与床位 slot。</p>
        <router-link class="el-button el-button--primary detail-btn" to="/frontdesk">改约 / 换技师</router-link>
        <div class="detail-split">
          <router-link class="el-button detail-btn" to="/frontdesk">加钟</router-link>
          <router-link class="el-button detail-btn" to="/frontdesk">取消退款</router-link>
        </div>
        <h2 style="margin-top: 20px">当日订单 · {{ selectedOrders.length }}</h2>
        <article
          v-for="o in selectedOrders"
          :key="o.orderId"
          class="detail-card"
          :class="{ alert: o.highlight }"
          @click="selectedOrder = o"
        >
          <div class="mono">{{ o.orderNo }}</div>
          <div>{{ STATUS_LABEL[o.status] || o.status }} · {{ yuanSafe(o.payableFen) }}</div>
        </article>
      </aside>
    </div>
  </div>
</template>
