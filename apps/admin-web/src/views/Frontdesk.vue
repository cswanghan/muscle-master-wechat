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

type AddOnData = {
  orderId: string
  status: string
  payChannel: string
  paymentNo?: string | null
  codeUrl?: string | null
  amountFen: number
  endSlotNo: number
}

type SwapData = {
  orderId: string
  status: string
  oldTherapistId: string
  newTherapistId: string
  fromSlotNo: number
  replay: boolean
}

const STORE = '3100000000000000001'
const THERAPIST = '3100000000000000401'
const THERAPIST_CHEN = '3100000000000000402'
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

const addOnOrderId = ref('')
const addOnMinutes = ref(30)
const addOnChannel = ref<'CASH' | 'WECHAT'>('CASH')
const addOnLoading = ref(false)
const addOn = ref<AddOnData | null>(null)

const swapOrderId = ref('')
const swapTherapistId = ref(THERAPIST_CHEN)
const swapReason = ref('指定技师请假')
const swapLoading = ref(false)
const swapResult = ref<SwapData | null>(null)

const rescheduleOrderId = ref('')
const rescheduleDate = ref('2026-08-14')
const rescheduleStart = ref(80)
const rescheduleTherapistId = ref(THERAPIST)
const rescheduleLoading = ref(false)
const rescheduleResult = ref<{ status: string; serviceDate: string; startSlotNo: number; endSlotNo: number } | null>(null)

const refundOrderId = ref('')
const refundAmountFen = ref('0')
const refundLoading = ref(false)
const refundResult = ref<{ status: string; workflowStatus: string; refunds?: unknown[] } | null>(null)

const loggedIn = computed(() => token.value.length > 0)
const qrText = computed(() => addOn.value?.codeUrl || walkIn.value?.codeUrl || '')
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

async function submitAddOn() {
  addOnLoading.value = true
  error.value = ''
  addOn.value = null
  pollStatus.value = ''
  stopPoll()
  try {
    const orderId = addOnOrderId.value.trim() || checkInResult.value?.orderId || ''
    if (!orderId) {
      throw new Error('请先核销或填写订单号')
    }
    const res = await fetch(`/api/v1/f/orders/${orderId}/add-on`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `ao-${Date.now()}`,
        projectId: PROJECT,
        durationMinutes: addOnMinutes.value,
        payChannel: addOnChannel.value,
      }),
    })
    const data = await readEnvelope<AddOnData>(res)
    addOn.value = data
    addOnOrderId.value = data.orderId
    if (data.payChannel === 'WECHAT' && data.paymentNo) {
      await pollPayment(data.paymentNo, data.orderId, false)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    addOnLoading.value = false
  }
}

async function swapTherapist(orderId?: string) {
  const id = (orderId || swapOrderId.value).trim()
  if (!id) {
    error.value = '请填写订单'
    return
  }
  swapLoading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/orders/${id}/swap-therapist`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `sw-${Date.now()}`,
        newTherapistId: swapTherapistId.value.trim(),
        reason: swapReason.value.trim(),
      }),
    })
    swapResult.value = await readEnvelope<SwapData>(res)
    swapOrderId.value = swapResult.value.orderId
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    swapLoading.value = false
  }
}

async function submitReschedule() {
  const id = rescheduleOrderId.value.trim()
  if (!id) {
    error.value = '请填写订单'
    return
  }
  rescheduleLoading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/orders/${id}/reschedule`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `rs-${Date.now()}`,
        date: rescheduleDate.value,
        startSlotNo: rescheduleStart.value,
        therapistId: rescheduleTherapistId.value.trim(),
      }),
    })
    rescheduleResult.value = await readEnvelope(res)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    rescheduleLoading.value = false
  }
}

async function submitRefund() {
  const id = refundOrderId.value.trim()
  if (!id) {
    error.value = '请填写订单'
    return
  }
  refundLoading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/orders/${id}/refund`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `rf-${Date.now()}`,
        amountFen: Number(refundAmountFen.value) || 0,
        reason: '前台退款',
      }),
    })
    refundResult.value = await readEnvelope(res)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    refundLoading.value = false
  }
}

onUnmounted(stopPoll)
</script>

<template>
  <div id="frontdesk-page" class="desk">
    <header class="desk-bar">
      <div>
        <h1>门店前台</h1>
        <p>iPad 横屏 1024 · 核销 / 现金 / 微信收款码 / 加钟</p>
      </div>
      <el-button id="desk-login-btn" type="primary" :loading="loginLoading" @click="devLogin">
        {{ loggedIn ? staffName : '登录前台 demo.front' }}
      </el-button>
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

    <section class="desk-card" id="addon-panel">
      <h2>服务中加钟</h2>
      <p class="hint">IN_SERVICE · durationMinutes 为 15 的倍数 · CASH 当场 / WECHAT 收款码轮询</p>
      <div class="form">
        <label>订单</label>
        <el-input
          id="addon-order"
          v-model="addOnOrderId"
          size="large"
          :placeholder="checkInResult?.orderId || '服务中订单 ID'"
        />
        <label>加钟时长 / 支付</label>
        <div class="row">
          <el-select id="addon-minutes" v-model="addOnMinutes" size="large" style="width: 160px">
            <el-option :value="15" label="15 分钟" />
            <el-option :value="30" label="30 分钟" />
            <el-option :value="45" label="45 分钟" />
          </el-select>
          <el-radio-group id="addon-channel" v-model="addOnChannel" size="large">
            <el-radio-button value="CASH">现金</el-radio-button>
            <el-radio-button value="WECHAT">微信收款码</el-radio-button>
          </el-radio-group>
        </div>
        <el-button
          id="addon-submit"
          type="primary"
          size="large"
          :loading="addOnLoading"
          @click="submitAddOn"
        >
          确认加钟
        </el-button>
      </div>
      <div v-if="addOn" id="addon-result" class="result">
        {{ addOn.status }} · {{ addOn.payChannel }} · ¥{{ (addOn.amountFen / 100).toFixed(0) }} ·
        结束格 {{ addOn.endSlotNo }}
      </div>
    </section>

    <section class="desk-card" id="swap-panel">
      <h2>换技师</h2>
      <p class="hint">只锁新技师剩余格 · 不重锁本单床 · POST /f/orders/{id}/swap-therapist</p>
      <div class="form">
        <label>订单 ID</label>
        <el-input id="swap-order-id" v-model="swapOrderId" size="large" placeholder="核销后填入或从查找带入" />
        <label>新技师 ID</label>
        <el-input id="swap-therapist-id" v-model="swapTherapistId" size="large" />
        <label>原因</label>
        <el-input id="swap-reason" v-model="swapReason" size="large" />
        <el-button
          id="swap-submit"
          type="primary"
          size="large"
          :loading="swapLoading"
          @click="swapTherapist()"
        >
          确认换师
        </el-button>
      </div>
      <div v-if="swapResult" id="swap-result" class="result">
        {{ swapResult.status }} · {{ swapResult.oldTherapistId }} → {{ swapResult.newTherapistId }} ·
        from={{ swapResult.fromSlotNo }}
      </div>
    </section>

    <section class="desk-card" id="reschedule-panel">
      <h2>改约</h2>
      <p class="hint">仅 BOOKED · 同店同项目同价 · POST /f/orders/{id}/reschedule</p>
      <div class="form">
        <label>订单 ID</label>
        <el-input id="reschedule-order-id" v-model="rescheduleOrderId" size="large" />
        <label>新日期 / 开始格 / 技师</label>
        <div class="row">
          <el-input id="reschedule-date" v-model="rescheduleDate" size="large" />
          <el-input-number id="reschedule-start" v-model="rescheduleStart" :min="0" size="large" />
          <el-input id="reschedule-therapist" v-model="rescheduleTherapistId" size="large" />
        </div>
        <el-button id="reschedule-submit" type="primary" size="large" :loading="rescheduleLoading" @click="submitReschedule">
          确认改约
        </el-button>
      </div>
      <div v-if="rescheduleResult" id="reschedule-result" class="result">
        {{ rescheduleResult.status }} · {{ rescheduleResult.serviceDate }} 格{{ rescheduleResult.startSlotNo }}–{{ rescheduleResult.endSlotNo }}
      </div>
    </section>

    <section class="desk-card" id="refund-panel">
      <h2>退款</h2>
      <p class="hint">全额 = SUM(SUCCESS)−已退 · ≥50000 先审批 · POST /f/orders/{id}/refund</p>
      <div class="form">
        <label>订单 ID / 金额（分，锁定为剩余可退）</label>
        <div class="row">
          <el-input id="refund-order-id" v-model="refundOrderId" size="large" />
          <el-input id="refund-amount" v-model="refundAmountFen" size="large" disabled />
        </div>
        <el-button id="refund-submit" type="primary" size="large" :loading="refundLoading" @click="submitRefund">
          发起退款
        </el-button>
      </div>
      <div v-if="refundResult" id="refund-result" class="result">
        {{ refundResult.status }} · {{ refundResult.workflowStatus }} · {{ refundResult.refunds?.length || 0 }} 张
      </div>
    </section>

    <section v-if="walkIn || addOn" id="qr-panel" class="desk-card qr-card">
      <div>
        <h2>收款结果</h2>
        <p v-if="walkIn">
          {{ walkIn.orderNo }} · {{ walkIn.payChannel }} · {{ walkIn.status }} ·
          ¥{{ (walkIn.payableFen / 100).toFixed(0) }}
        </p>
        <p v-else-if="addOn">
          加钟 {{ addOn.orderId }} · {{ addOn.payChannel }} · {{ addOn.status }} ·
          ¥{{ (addOn.amountFen / 100).toFixed(0) }}
        </p>
        <p v-if="pollStatus" id="poll-status">
          轮询 {{ addOn?.paymentNo || walkIn?.paymentNo }} → {{ pollStatus }}
        </p>
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
  </div>
</template>
