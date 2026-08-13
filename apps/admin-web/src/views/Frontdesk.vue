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
  therapistId: string
  startSlotNo: number
  serviceDate: string
}

type RescheduleData = {
  orderId: string
  orderNo: string
  status: string
  therapistId: string
  serviceDate: string
  startSlotNo: number
  endSlotNo: number
  roomName: string
  bedName: string
  customerMask: string
  replay: boolean
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

const rsOrder = ref<LookupItem | null>(null)
const rsDate = ref('2026-08-14')
const rsStart = ref(64)
const rsTherapist = ref(THERAPIST)
const rsLoading = ref(false)
const rsResult = ref<RescheduleData | null>(null)

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

function selectReschedule(item: LookupItem) {
  rsOrder.value = item
  rsDate.value = item.serviceDate
  rsStart.value = item.startSlotNo
  rsTherapist.value = item.therapistId || THERAPIST
  rsResult.value = null
}

async function submitReschedule() {
  if (!rsOrder.value) {
    error.value = '先查找并选择要改约的订单'
    return
  }
  rsLoading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/v1/f/orders/${rsOrder.value.orderId}/reschedule`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        requestId: `rs-${Date.now()}`,
        date: rsDate.value,
        startSlotNo: rsStart.value,
        therapistId: rsTherapist.value,
      }),
    })
    rsResult.value = await readEnvelope<RescheduleData>(res)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    rsLoading.value = false
  }
}

onUnmounted(stopPoll)
</script>

<template>
  <div id="frontdesk-page" class="desk">
    <header class="desk-bar">
      <div>
        <h1>门店前台</h1>
        <p>iPad 横屏 1024 · 核销 / 现金 / 微信收款码 / 改约</p>
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
          <div class="row">
            <el-button
              type="primary"
              size="large"
              :loading="checkInLoading"
              @click="checkIn(item.orderId)"
            >
              核销到店
            </el-button>
            <el-button size="large" @click="selectReschedule(item)">改约</el-button>
          </div>
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

    <section class="desk-card" id="reschedule-panel">
      <h2>改约</h2>
      <p class="hint">仅 BOOKED · 同店同项目同价 · POST /f/orders/{id}/reschedule · 无 C 端</p>
      <div class="form">
        <label>订单</label>
        <el-input
          id="rs-order"
          :model-value="rsOrder ? `${rsOrder.orderNo} · ${rsOrder.status}` : '从核销结果点「改约」'"
          size="large"
          disabled
        />
        <label>日期 / 起始格</label>
        <div class="row">
          <el-input id="rs-date" v-model="rsDate" size="large" />
          <el-input-number id="rs-start" v-model="rsStart" :min="40" :max="87" size="large" />
        </div>
        <label>技师</label>
        <el-radio-group id="rs-therapist" v-model="rsTherapist" size="large">
          <el-radio-button :value="THERAPIST">林晓</el-radio-button>
          <el-radio-button :value="THERAPIST_CHEN">陈默</el-radio-button>
        </el-radio-group>
        <el-button
          id="rs-submit"
          type="primary"
          size="large"
          :loading="rsLoading"
          @click="submitReschedule"
        >
          确认改约
        </el-button>
      </div>
      <div v-if="rsResult" id="rs-result" class="result">
        {{ rsResult.status }} · {{ rsResult.serviceDate }} #{{ rsResult.startSlotNo }}–{{
          rsResult.endSlotNo
        }}
        · {{ rsResult.roomName }} {{ rsResult.bedName }}
      </div>
    </section>

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
  </div>
</template>
