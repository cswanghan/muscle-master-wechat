<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '../api'

type CustomerRow = {
  customerId: string; customerMask: string; wxNickname: string | null
  remainingSessions: number; expireOn: string | null
  lastVisitOn: string | null; dormantDays: number; ownerTherapistName: string
}
type CustomerReport = { kind: string; kindLabel: string; rows: CustomerRow[]; total: number }
type BoardRow = {
  therapistId: string; therapistName: string
  lessonCount: number; salesYuan: string
  positiveRateX100: number; renewRateX100: number
  convertRateX100: number; followUpRateX100: number
}
type ClaimItem = {
  claimId: string; platformLabel: string; rating: number
  therapistName: string; proofUrl: string | null
  claimedOn: string; status: string; statusLabel: string
}

const KINDS = [
  { v: 'renew', l: '应续费' },
  { v: 'expired', l: '到期未续费' },
  { v: 'dormant', l: '长期未到访' },
]

const tab = ref('customers')
const kind = ref('renew')
const month = ref('')
const loading = ref(false)
const error = ref('')

const report = ref<CustomerReport | null>(null)
const board = ref<BoardRow[]>([])
const claims = ref<ClaimItem[]>([])
const claimPending = ref(0)

function pct(x: number) {
  return Math.round((x ?? 0) / 100) + '%'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (tab.value === 'customers') {
      report.value = await request<CustomerReport>(`/api/v1/f/finance/reports/customers?kind=${kind.value}`)
    }
    if (tab.value === 'board') {
      board.value = await request<BoardRow[]>(
        `/api/v1/f/therapist-board${month.value ? `?month=${month.value}` : ''}`)
    }
    if (tab.value === 'claims') {
      const d = await request<{ items: ClaimItem[]; pending: number }>('/api/v1/f/review-claims')
      claims.value = d.items ?? []
      claimPending.value = d.pending ?? 0
    }
  } catch (e) {
    error.value = (e as Error).message || '加载失败'
  } finally {
    loading.value = false
  }
}

function pickTab(k: string) {
  tab.value = k
  load()
}

function pickKind(k: string) {
  kind.value = k
  load()
}

async function decideClaim(row: ClaimItem, approve: boolean) {
  try {
    await request(`/api/v1/f/review-claims/${row.claimId}/${approve ? 'approve' : 'reject'}`,
      { method: 'POST' })
    ElMessage.success(approve ? '已通过' : '已驳回')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="members">
    <header class="head">
      <div>
        <h1>客户与老师</h1>
        <p class="label">
          客户报表按「该做什么」分组而不是按状态罗列：应续费的是还有课但快没了的人，
          这时候提最有效；已经归零的归到「到期未续费」，话术不一样。
        </p>
      </div>
      <div class="acts">
        <el-input v-if="tab === 'board'" v-model="month" placeholder="2026-09" style="width: 120px" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <div class="tabs">
      <button :class="{ on: tab === 'customers' }" @click="pickTab('customers')">客户报表</button>
      <button :class="{ on: tab === 'board' }" @click="pickTab('board')">老师对比</button>
      <button :class="{ on: tab === 'claims' }" @click="pickTab('claims')">
        好评认领<span v-if="claimPending" class="dot">{{ claimPending }}</span>
      </button>
    </div>

    <section v-if="tab === 'customers'" class="pane">
      <div class="segs">
        <button v-for="k in KINDS" :key="k.v" :class="{ on: kind === k.v }" @click="pickKind(k.v)">
          {{ k.l }}
        </button>
      </div>
      <p v-if="report" class="sum">{{ report.kindLabel }} · {{ report.total }} 人</p>
      <el-table :data="report?.rows ?? []" stripe v-loading="loading">
        <el-table-column prop="customerMask" label="会员" width="140" />
        <el-table-column prop="wxNickname" label="微信名" width="140" />
        <el-table-column label="剩余课时" width="100">
          <template #default="{ row }">
            <span :class="{ warn: row.remainingSessions <= 0 }">{{ row.remainingSessions }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="expireOn" label="到期" width="130" />
        <el-table-column prop="lastVisitOn" label="最近到访" width="130" />
        <el-table-column label="已多久没来" width="120">
          <template #default="{ row }">
            <span v-if="row.dormantDays >= 0">{{ row.dormantDays }} 天</span>
            <span v-else class="muted">从未到访</span>
          </template>
        </el-table-column>
        <el-table-column prop="ownerTherapistName" label="归属老师" />
      </el-table>
    </section>

    <section v-if="tab === 'board'" class="pane">
      <p class="sum">本店老师横向对比，按上课量排</p>
      <el-table :data="board" stripe v-loading="loading">
        <el-table-column prop="therapistName" label="姓名" width="110" />
        <el-table-column prop="lessonCount" label="上课" width="90" />
        <el-table-column prop="salesYuan" label="销售额" width="120" />
        <el-table-column label="新客好评率" width="120">
          <template #default="{ row }">{{ pct(row.positiveRateX100) }}</template>
        </el-table-column>
        <el-table-column label="续费率" width="100">
          <template #default="{ row }">{{ pct(row.renewRateX100) }}</template>
        </el-table-column>
        <el-table-column label="转成交率" width="110">
          <template #default="{ row }">{{ pct(row.convertRateX100) }}</template>
        </el-table-column>
        <el-table-column label="回访完成度">
          <template #default="{ row }">
            <span :class="{ warn: row.followUpRateX100 < 6000 }">{{ pct(row.followUpRateX100) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-if="tab === 'claims'" class="pane">
      <p class="sum">外部平台好评由老师提交截图认领，审核通过后才计入好评率</p>
      <el-table :data="claims" stripe v-loading="loading">
        <el-table-column prop="claimedOn" label="日期" width="120" />
        <el-table-column prop="therapistName" label="老师" width="110" />
        <el-table-column prop="platformLabel" label="平台" width="120" />
        <el-table-column prop="rating" label="评分" width="80" />
        <el-table-column label="凭证" width="90">
          <template #default="{ row }">
            <a v-if="row.proofUrl" :href="row.proofUrl" target="_blank" rel="noreferrer">查看</a>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'APPROVED' ? 'success'
              : (row.status === 'REJECTED' ? 'danger' : 'warning')">
              {{ row.statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="primary" @click="decideClaim(row, true)">通过</el-button>
              <el-button size="small" @click="decideClaim(row, false)">驳回</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.members { padding: 24px; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.label { color: #8b948f; font-size: 13px; margin: 4px 0 0; max-width: 640px; line-height: 1.6; }
.acts { display: flex; gap: 8px; flex-shrink: 0; }

.tabs, .segs { display: flex; gap: 6px; flex-wrap: wrap; margin: 16px 0; }
.tabs button, .segs button {
  padding: 7px 16px; border: 1px solid #dfe5e3; background: #fff; border-radius: 999px;
  font-size: 13.5px; cursor: pointer; color: #3d4d46;
}
.tabs button.on, .segs button.on { background: #1e5c4a; border-color: #1e5c4a; color: #fff; }
.dot { margin-left: 6px; background: #c2760c; color: #fff; border-radius: 999px; padding: 0 6px; font-size: 11px; }

.pane { margin-top: 4px; }
.sum { color: #3d4d46; font-size: 14px; margin: 0 0 12px; }
.warn { color: #c2760c; font-weight: 600; }
.muted { color: #8b948f; }
</style>
