<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { request } from '../api'

type StaffItem = {
  staffId: string
  username: string
  name: string
  status: number
  statusLabel: string
  roleCodes: string[]
  scopeType: string
  therapistId: string | null
  level: string | null
  pendingOrders: number
}

type BlockedOrder = {
  orderId: string
  orderNo: string
  date: string
  start: string
  status: string
}

type ActionResult = {
  staffId: string
  therapistId: string | null
  action: string
  status: number
  effectiveOn: string | null
  blockedOrders: BlockedOrder[]
}

type LogItem = {
  logId: string
  staffId: string
  staffName: string
  action: string
  effectiveOn: string | null
  reason: string | null
  createdAt: string
}

type StoreItem = { storeId: string; name: string }

const ROLE_NAME: Record<string, string> = {
  THERAPIST: '技师', FRONTDESK: '前台', STORE_MANAGER: '店长', OPS: '运营',
  SUPER_ADMIN: '超管', FINANCE: '财务', REGION_MANAGER: '区域经理',
}
// 和后端 GRANTABLE 一致：超管 / 财务不从这里发，避免拿着 staff:manage 就能给自己提权。
const GRANTABLE = ['THERAPIST', 'FRONTDESK', 'STORE_MANAGER', 'OPS']
const ACTION_NAME: Record<string, string> = {
  ONBOARD: '入职', OFFBOARD: '离职', REHIRE: '复职',
}
const LEVEL_NAME: Record<string, string> = {
  JUNIOR: '初级', MIDDLE: '中级', SENIOR: '资深', CHIEF: '首席',
}

const staff = ref<StaffItem[]>([])
const logs = ref<LogItem[]>([])
const stores = ref<StoreItem[]>([])
const statusFilter = ref<string>('1')
const loading = ref(false)
const error = ref('')

const hireOpen = ref(false)
const hiring = ref(false)
const form = ref({
  username: '', name: '', roleCodes: ['THERAPIST'] as string[],
  storeIds: [] as string[], effectiveOn: '',
})

const blockedOpen = ref(false)
const blocked = ref<BlockedOrder[]>([])
const blockedFor = ref<StaffItem | null>(null)

const onDuty = computed(() => staff.value.filter((s) => s.status === 1).length)

function roleName(code: string) {
  return ROLE_NAME[code] ?? code
}

function levelName(code: string | null) {
  return code ? (LEVEL_NAME[code] ?? code) : '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [roster, trail] = await Promise.all([
      request<{ items: StaffItem[] }>(`/api/v1/a/employment/staff?status=${statusFilter.value}`),
      request<{ items: LogItem[] }>('/api/v1/a/employment/logs'),
    ])
    staff.value = roster.items ?? []
    logs.value = trail.items ?? []
  } catch (e) {
    error.value = (e as Error).message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function openHire() {
  form.value = {
    username: '', name: '', roleCodes: ['THERAPIST'], storeIds: [], effectiveOn: '',
  }
  hireOpen.value = true
  if (!stores.value.length) {
    // 优先走 admin 口，它按操作人的数据域过滤；但店长没有 catalog:store 权限，
    // 那边会 403，所以退到 C 端门店列表（灰度内的店，够用）。
    try {
      const data = await request<{ items: StoreItem[] }>('/api/v1/a/stores')
      stores.value = data.items ?? []
    } catch {
      try {
        const data = await request<{ items: StoreItem[] }>('/api/v1/c/stores')
        stores.value = data.items ?? []
      } catch {
        ElMessage.warning('门店列表没拉到，技师入职需要门店，稍后重试')
      }
    }
  }
}

async function submitHire() {
  if (!form.value.username.trim() || !form.value.name.trim()) {
    ElMessage.warning('登录名和姓名都要填')
    return
  }
  if (form.value.roleCodes.includes('THERAPIST') && !form.value.storeIds.length) {
    ElMessage.warning('技师必须选归属门店，否则排不了班')
    return
  }
  hiring.value = true
  try {
    const data = await request<ActionResult>('/api/v1/a/employment/onboard', {
      method: 'POST',
      body: JSON.stringify({ requestId: 'hire-' + Date.now(), ...form.value }),
    })
    ElMessage.success(data.therapistId ? '入职成功，已建技师档案' : '入职成功')
    hireOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '入职失败')
  } finally {
    hiring.value = false
  }
}

// 后端在有未完成订单时返回 status 不变 + blocked 清单（不是报错），
// 这里把单子摆出来让人先处理，force 只在人看过之后才给。
async function offboard(row: StaffItem, force = false) {
  if (!force) {
    await ElMessageBox.confirm(
      `确认 ${row.name} 离职？账号停用，历史订单和业绩都保留，可以再复职。`,
      '员工离职', { type: 'warning' },
    )
  }
  try {
    const data = await request<ActionResult>(`/api/v1/a/employment/${row.staffId}/offboard`, {
      method: 'POST',
      body: JSON.stringify({ requestId: 'off-' + Date.now(), force }),
    })
    if (data.status === 1 && data.blockedOrders.length) {
      blocked.value = data.blockedOrders
      blockedFor.value = row
      blockedOpen.value = true
      return
    }
    ElMessage.success('已办理离职')
    blockedOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '离职失败')
  }
}

async function forceOffboard() {
  if (!blockedFor.value) {
    return
  }
  await ElMessageBox.confirm(
    `${blockedFor.value.name} 名下还有 ${blocked.value.length} 笔未完成订单。`
      + '强制离职不会自动改约或退款，请先在订单中心处理完再来。确认继续？',
    '强制离职', { type: 'error', confirmButtonText: '仍要离职' },
  )
  await offboard(blockedFor.value, true)
}

async function rehire(row: StaffItem) {
  await ElMessageBox.confirm(`确认 ${row.name} 复职？账号和历史都还在，直接恢复在职。`, '员工复职')
  try {
    await request(`/api/v1/a/employment/${row.staffId}/rehire`, {
      method: 'POST', body: JSON.stringify({ requestId: 're-' + Date.now() }),
    })
    ElMessage.success('已办理复职')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '复职失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="employment">
    <header class="dash-head">
      <div>
        <h1>员工入离职</h1>
        <p class="label">
          离职是停用不是删除：历史订单、业绩、评价都挂在这个账号上，删了就断线索。
          名下还有未完成订单时离职会被挡下并列出冲突单，处理完再走。
        </p>
      </div>
      <div class="acts">
        <el-radio-group v-model="statusFilter" @change="load">
          <el-radio-button label="1">在职</el-radio-button>
          <el-radio-button label="0">已离职</el-radio-button>
          <el-radio-button label="">全部</el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button id="hire-btn" type="primary" @click="openHire">办理入职</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-table id="staff-table" :data="staff" stripe v-loading="loading">
      <el-table-column prop="name" label="姓名" width="120" />
      <el-table-column prop="username" label="登录名" width="140" />
      <el-table-column label="角色" width="160">
        <template #default="{ row }">
          <el-tag v-for="c in row.roleCodes" :key="c" size="small" class="role">{{ roleName(c) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="技师等级" width="100">
        <template #default="{ row }">{{ row.therapistId ? levelName(row.level) : '—' }}</template>
      </el-table-column>
      <el-table-column label="未完成订单" width="120">
        <template #default="{ row }">
          <span :class="{ warn: row.pendingOrders > 0 }">{{ row.pendingOrders }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" size="small" @click="offboard(row)">离职</el-button>
          <el-button v-else size="small" type="primary" @click="rehire(row)">复职</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p class="tally">在职 {{ onDuty }} 人 · 共 {{ staff.length }} 条</p>

    <h2 class="sec">最近异动</h2>
    <el-table id="employment-log-table" :data="logs" stripe size="small">
      <el-table-column prop="staffName" label="员工" width="120" />
      <el-table-column label="动作" width="90">
        <template #default="{ row }">{{ ACTION_NAME[row.action] ?? row.action }}</template>
      </el-table-column>
      <el-table-column prop="effectiveOn" label="生效日" width="120" />
      <el-table-column prop="reason" label="原因" />
      <el-table-column prop="createdAt" label="操作时间" width="220" />
    </el-table>
    <p v-if="!logs.length" class="empty">暂无异动记录</p>

    <el-dialog v-model="hireOpen" title="办理入职" width="480px">
      <el-form label-width="90px">
        <el-form-item label="登录名">
          <el-input v-model="form.username" placeholder="英文，登录用，建好不改" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.name" placeholder="顾客看到的名字" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleCodes" multiple style="width: 100%">
            <el-option v-for="c in GRANTABLE" :key="c" :label="roleName(c)" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属门店">
          <el-select v-model="form.storeIds" multiple style="width: 100%">
            <el-option v-for="s in stores" :key="s.storeId" :label="s.name" :value="s.storeId" />
          </el-select>
        </el-form-item>
        <el-form-item label="入职日">
          <el-date-picker v-model="form.effectiveOn" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
      </el-form>
      <p class="hint">
        选了「技师」会同时建技师档案，默认接本店在售的全部项目、初级等级，之后在项目 SKU 里再调。
        超管和财务不从这里发。
      </p>
      <template #footer>
        <el-button @click="hireOpen = false">取消</el-button>
        <el-button type="primary" :loading="hiring" @click="submitHire">确认入职</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="blockedOpen" title="还有未完成订单" width="560px">
      <p class="hint">
        {{ blockedFor?.name }} 名下这些单还在进行中。直接停用会让顾客到店找不到人，
        请先在订单中心改约或退款。
      </p>
      <el-table :data="blocked" size="small">
        <el-table-column prop="orderNo" label="订单号" width="180" />
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="start" label="到店" width="80" />
        <el-table-column prop="status" label="状态" />
      </el-table>
      <template #footer>
        <el-button @click="blockedOpen = false">先去处理</el-button>
        <el-button type="danger" @click="forceOffboard">仍要离职</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.employment { padding: 24px; }
.dash-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 16px; }
.label { color: #8b948f; font-size: 13px; margin: 4px 0 0; max-width: 640px; line-height: 1.6; }
.acts { display: flex; gap: 8px; align-items: center; flex-shrink: 0; }
.role { margin-right: 4px; }
.warn { color: #d97706; font-weight: 600; }
.tally { color: #8b948f; font-size: 13px; margin: 8px 0 0; }
.sec { font-size: 15px; margin: 28px 0 12px; }
.hint { color: #8b948f; font-size: 12px; line-height: 1.7; margin: 0 0 12px; }
.empty { color: #8b948f; text-align: center; padding: 24px 0; }
</style>
