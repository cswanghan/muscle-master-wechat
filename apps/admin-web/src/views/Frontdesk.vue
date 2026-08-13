<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { qrSvg } from '../qr'

type Envelope<T> = {
  code: number
  message: string
  requestId?: string
  data: T
}

type LoginData = {
  token: string
  expiresIn: number
  staffId?: string
  typ?: string
  name?: string
}

type LookupItem = {
  orderId: string
  orderNo: string
  status: string
  roomName: string
  bedName: string
  customerMask: string
  startSlotNo: number
  serviceDate: string
  payableFen?: number
}

type CheckInData = {
  orderId: string
  status: string
  roomName: string
  bedName: string
  customerMask: string
}

type WalkInData = {
  orderId: string
  orderNo: string
  status: string
  customerId: string
  payChannel: string
  paymentNo: string
  codeUrl?: string | null
  payableFen: number
  alreadyInStore: boolean
  customerMask: string
  roomName?: string
  bedName?: string
}

const STORE = '3100000000000000001'
const THERAPIST = '3100000000000000401'
const PROJECT = '3100000000000000501'

const token = ref('')
const staffName = ref('')
const error = ref('')
const loginLoading = ref(false)

const keyword = ref('')
const lookupItems = ref<LookupItem[]>([])
const checkInResult = ref<CheckInData | null>(null)
const lookupLoading = ref(false)
const checkInLoading = ref(false)

const phone = ref('18600001111')
const customerName = ref('王先生')
const date = ref('2026-08-14')
const startSlotNo = ref(64)
const alreadyInStore = ref(true)
const payChannel = ref<'CASH' | 'WECHAT'>('WECHAT')
const walkLoading = ref(false)
const walkIn = ref<WalkInData | null>(null)
const pollStatus = ref('')
let pollTimer: number | undefined

const refundOrderId = ref('')
const refundAmount = ref(19800)
const refundReason = ref('客户改期无法改约')
const refundLoading = ref(false)
const refundResult = ref<RefundData | null>(null)

const taskLoading = ref(false)
const approveLoading = ref('')
const humanTasks = ref<HumanTaskItem[]>([])

type RefundData = {
  orderId: string
  status: string
  workflowStatus: string
  replay?: boolean
  refunds: Array<{
    refundNo: string
    paymentId: string
    amountFen: number
    status: string
    wxRefundId?: string | null
  }>
}

type HumanTaskItem = {
  id: string
  taskType: string
  title: string
  status: string
  orderId?: string | null
  bizKey?: string | null
}

const loggedIn = computed(() => token.value.length > 0)
const qrText = computed(() => walkIn.value?.codeUrl ?? '')
const qrMarkup = computed(() => (qrText.value ? qrSvg(qrText.value) : ''))

function authHeaders(): HeadersInit {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token.value) {
    headers.Authorization = `Bearer ${token.value}`
  }
  return headers
}

async function readEnvelope<T>(res: Response): Promise<T> {
  const body = (await res.json()) as Envelope<T>
  if (!res.ok || body.code !== 0) {
    throw new Error(body.message || `HTTP ${res.status}`)
  }
  return body.data
}

async function devLogin() {
  loginLoading.value = true
  error.value = ''
  try {
    const res = await fetch('/api/v1/staff/auth/wechat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'dev-staff-front' }),
    })
    const data = await readEnvelope<LoginData>(res)
    token.value = data.token
    staffName.value = data.name || '前台'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loginLoading.value = false
  }
}

async function lookup() {
  lookupLoading.value = true
  error.value = ''
  checkInResult.value = null
  try {
    const q = encodeURIComponent(keyword.value.trim())
    const res = await fetch(`/api/v1/f/orders/lookup?keyword=${q}`, { headers: authHeaders() })
    const data = await readEnvelope<{ items: LookupItem[] }>(res)
    lookupItems.value = data.items
    const first = data.items[0]
    if (first) {
      fillRefund(first.orderId, first.payableFen ?? refundAmount.value)
    }
  } catch (e) {
    lookupItems.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    lookupLoading.value = false
  }
}

async function checkIn(orderId: string, verify?: string, kw?: string) {
  checkInLoading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/orders/${orderId}/check-in`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `ci-${Date.now()}`,
        verify: verify || (keyword.value.trim().startsWith('JS') ? 'ORDER_NO' : 'PHONE'),
        keyword: kw || keyword.value.trim(),
      }),
    })
    checkInResult.value = await readEnvelope<CheckInData>(res)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    checkInLoading.value = false
  }
}

function stopPoll() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function pollPayment(paymentNo: string, orderId: string, shouldCheckIn: boolean) {
  stopPoll()
  pollStatus.value = 'PENDING'
  pollTimer = window.setInterval(async () => {
    try {
      const res = await fetch(`/api/v1/f/payments/${paymentNo}`, { headers: authHeaders() })
      const view = await readEnvelope<{ status: string }>(res)
      pollStatus.value = view.status
      if (view.status === 'SUCCESS' || view.status === 'CLOSED' || view.status === 'FAILED') {
        stopPoll()
        if (view.status === 'SUCCESS' && shouldCheckIn) {
          await checkIn(orderId, 'PHONE', phone.value)
        }
      }
    } catch (e) {
      pollStatus.value = e instanceof Error ? e.message : String(e)
    }
  }, 1500)
}

async function submitWalkIn() {
  walkLoading.value = true
  error.value = ''
  walkIn.value = null
  pollStatus.value = ''
  stopPoll()
  try {
    const res = await fetch('/api/v1/f/walk-ins', {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `wi-${Date.now()}`,
        phone: phone.value,
        customerName: customerName.value,
        storeId: STORE,
        therapistId: THERAPIST,
        projectId: PROJECT,
        date: date.value,
        startSlotNo: startSlotNo.value,
        alreadyInStore: alreadyInStore.value,
        payChannel: payChannel.value,
      }),
    })
    const data = await readEnvelope<WalkInData>(res)
    walkIn.value = data
    fillRefund(data.orderId, data.payableFen)
    if (data.payChannel === 'WECHAT' && data.paymentNo) {
      await pollPayment(data.paymentNo, data.orderId, data.alreadyInStore)
    }
    if (data.payChannel === 'CASH' && data.status === 'CHECKED_IN') {
      checkInResult.value = {
        orderId: data.orderId,
        status: data.status,
        roomName: data.roomName || '',
        bedName: data.bedName || '',
        customerMask: data.customerMask,
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    walkLoading.value = false
  }
}

function fillRefund(orderId: string, remaining: number) {
  refundOrderId.value = orderId
  refundAmount.value = remaining
}

async function submitRefund() {
  refundLoading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/orders/${refundOrderId.value.trim()}/refund`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `rf-${Date.now()}`,
        amountFen: Number(refundAmount.value),
        reason: refundReason.value,
      }),
    })
    refundResult.value = await readEnvelope<RefundData>(res)
    await loadTasks()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    refundLoading.value = false
  }
}

async function loadTasks() {
  taskLoading.value = true
  error.value = ''
  try {
    const res = await fetch('/api/v1/f/human-tasks?status=OPEN', { headers: authHeaders() })
    const data = await readEnvelope<{ items: HumanTaskItem[] }>(res)
    humanTasks.value = data.items
  } catch (e) {
    humanTasks.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    taskLoading.value = false
  }
}

async function denyTask(id: string) {
  approveLoading.value = id
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/human-tasks/${id}/deny`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ requestId: `dn-${Date.now()}` }),
    })
    refundResult.value = await readEnvelope<RefundData>(res)
    await loadTasks()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    approveLoading.value = ''
  }
}

async function approveTask(id: string) {
  approveLoading.value = id
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/human-tasks/${id}/approve`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ requestId: `ap-${Date.now()}` }),
    })
    refundResult.value = await readEnvelope<RefundData>(res)
    await loadTasks()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    approveLoading.value = ''
  }
}

async function managerLogin() {
  loginLoading.value = true
  error.value = ''
  try {
    const res = await fetch('/api/v1/staff/auth/wechat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'dev-staff-manager' }),
    })
    const data = await readEnvelope<LoginData>(res)
    token.value = data.token
    staffName.value = data.name || '店长'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loginLoading.value = false
  }
}

onUnmounted(stopPoll)
</script>

<template>
  <div id="frontdesk-page" class="desk">
    <header class="desk-bar">
      <div>
        <h1>门店前台</h1>
        <p>iPad 横屏 1024 · 核销 / 现金 / 微信收款码 / 退款</p>
      </div>
      <div class="row">
        <el-button id="desk-login-btn" type="primary" :loading="loginLoading" @click="devLogin">
          {{ loggedIn ? staffName : '登录前台 demo.front' }}
        </el-button>
        <el-button id="desk-mgr-btn" :loading="loginLoading" @click="managerLogin">店长审批</el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      title="前台操作失败"
      type="error"
      :description="error"
      show-icon
      :closable="false"
      class="desk-alert"
    />

    <div class="desk-grid">
      <section class="desk-card" id="checkin-panel">
        <h2>到店核销</h2>
        <p class="hint">单号 JS… 或 11 位手机 · POST /f/orders/{id}/check-in</p>
        <div class="row">
          <el-input
            id="checkin-keyword"
            v-model="keyword"
            size="large"
            placeholder="JS20260814… 或 18600001111"
            clearable
          />
          <el-button size="large" :loading="lookupLoading" @click="lookup">查找</el-button>
        </div>
        <article v-for="item in lookupItems" :key="item.orderId" class="hit">
          <div>
            <strong>{{ item.orderNo }}</strong>
            <span>{{ item.customerMask }} · {{ item.roomName }} {{ item.bedName }}</span>
            <em>{{ item.status }}</em>
          </div>
          <el-button
            type="primary"
            size="large"
            :loading="checkInLoading"
            @click="checkIn(item.orderId)"
          >
            核销到店
          </el-button>
        </article>
        <div v-if="checkInResult" id="checkin-result" class="result">
          {{ checkInResult.status }} · {{ checkInResult.roomName }} {{ checkInResult.bedName }} ·
          {{ checkInResult.customerMask }}
        </div>
      </section>

      <section class="desk-card" id="walkin-panel">
        <h2>散客开单</h2>
        <p class="hint">手机必填 · CustomerMerge · CASH 当场 / WECHAT Native 轮询</p>
        <div class="form">
          <label>手机</label>
          <el-input id="walkin-phone" v-model="phone" size="large" maxlength="11" />
          <label>称呼</label>
          <el-input v-model="customerName" size="large" />
          <label>日期 / 起始格</label>
          <div class="row">
            <el-input v-model="date" size="large" />
            <el-input-number v-model="startSlotNo" :min="40" :max="87" size="large" />
          </div>
          <label>支付</label>
          <el-radio-group id="walkin-channel" v-model="payChannel" size="large">
            <el-radio-button value="CASH">现金</el-radio-button>
            <el-radio-button value="WECHAT">微信收款码</el-radio-button>
          </el-radio-group>
          <el-checkbox v-model="alreadyInStore">已在店（支付后核销）</el-checkbox>
          <el-button
            id="walkin-submit"
            type="primary"
            size="large"
            :loading="walkLoading"
            @click="submitWalkIn"
          >
            开单收款
          </el-button>
        </div>
      </section>
    </div>

    <section v-if="walkIn" id="qr-panel" class="desk-card qr-card">
      <div>
        <h2>收款结果</h2>
        <p>
          {{ walkIn.orderNo }} · {{ walkIn.payChannel }} · {{ walkIn.status }} ·
          ¥{{ (walkIn.payableFen / 100).toFixed(0) }}
        </p>
        <p v-if="pollStatus" id="poll-status">轮询 {{ walkIn.paymentNo }} → {{ pollStatus }}</p>
      </div>
      <div v-if="qrText" class="qr-box">
        <div
          v-if="qrMarkup"
          id="native-qr"
          class="qr-svg"
          v-html="qrMarkup"
        />
        <div v-else class="qr-mock" title="qr fallback" />
        <code>{{ qrText }}</code>
      </div>
    </section>

    <div class="desk-grid">
      <section class="desk-card" id="refund-panel">
        <h2>按支付单退款</h2>
        <p class="hint">P0 全额 = SUM(SUCCESS)−已退。金额锁定为 remaining，不符则 40001。≥¥500 审批=放款（订单已取消）。</p>
        <div class="form">
          <label>订单 ID</label>
          <el-input id="refund-order" v-model="refundOrderId" size="large" placeholder="orderId" />
          <label>待退金额（分，锁定 remaining）</label>
          <el-input-number id="refund-amount" v-model="refundAmount" :min="1" size="large" disabled />
          <label>原因</label>
          <el-input id="refund-reason" v-model="refundReason" size="large" />
          <el-button
            id="refund-submit"
            type="primary"
            size="large"
            :loading="refundLoading"
            @click="submitRefund"
          >
            发起退款
          </el-button>
        </div>
        <div v-if="refundResult" id="refund-result" class="result">
          {{ refundResult.status }} · {{ refundResult.workflowStatus }} ·
          {{ refundResult.refunds.length }} 张
          <span v-for="row in refundResult.refunds" :key="row.refundNo">
            {{ row.refundNo }}={{ row.status }}
          </span>
        </div>
      </section>

      <section class="desk-card" id="approve-panel">
        <h2>退款审批</h2>
        <p class="hint">审批=放款，不是决定是否取消。拒绝后订单仍取消、钱未退，生成 REFUND_DENIED。</p>
        <el-button id="task-refresh" size="large" :loading="taskLoading" @click="loadTasks">刷新待办</el-button>
        <article v-for="task in humanTasks" :key="task.id" class="hit">
          <div>
            <strong>{{ task.taskType }}</strong>
            <span>{{ task.title }} · 单 {{ task.orderId }}</span>
            <em>{{ task.status }}</em>
          </div>
          <div v-if="task.taskType === 'REFUND_APPROVE'" class="row">
            <el-button
              type="primary"
              size="large"
              :loading="approveLoading === task.id"
              @click="approveTask(task.id)"
            >
              审批放款
            </el-button>
            <el-button
              size="large"
              :loading="approveLoading === task.id"
              @click="denyTask(task.id)"
            >
              拒绝放款
            </el-button>
          </div>
        </article>
        <p v-if="humanTasks.length === 0" class="hint">暂无 OPEN 任务</p>
      </section>
    </div>
  </div>
</template>
