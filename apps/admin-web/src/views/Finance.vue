<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { request } from '../api'

type AmountRow = { key: string; label: string; amountYuan: string }
type StoreReport = {
  month: string
  income: AmountRow[]; incomeTotalYuan: string
  expense: AmountRow[]; expenseTotalYuan: string
  profitYuan: string; profitable: boolean
}
type ConsumeReport = { month: string; rows: any[]; totalYuan: string; totalLessons: number }
type TrialReport = {
  month: string; rows: any[]; totalCount: number; totalYuan: string
  convertedCount: number; convertRateX100: number
  byChannel: { channel: string; channelLabel: string; count: number; converted: number; convertRateX100: number }[]
}
type RefundReport = { month: string; rows: any[]; totalYuan: string; totalCount: number }
type PayrollReport = { month: string; rows: any[]; totalYuan: string }
type InventoryReport = {
  asOf: string; rows: any[]; totalSessions: number
  totalValueYuan: string; expiringValueYuan: string
}
type ExpenseItem = {
  expenseId: string; categoryLabel: string; amountYuan: string; happenedOn: string
  vendor: string; remark: string; proofUrl: string
  status: string; statusLabel: string; submitterName: string; rejectReason: string
}

const CATEGORIES = [
  { v: 'MATERIAL', l: '物料' }, { v: 'RENT', l: '场地租金' },
  { v: 'PROPERTY', l: '物业费' }, { v: 'UTILITY', l: '水电费' },
  { v: 'PROMOTION', l: '推广支出' }, { v: 'OTHER', l: '其他' },
]

const tab = ref('store')
const month = ref('')
const loading = ref(false)
const error = ref('')

const store = ref<StoreReport | null>(null)
const consume = ref<ConsumeReport | null>(null)
const trial = ref<TrialReport | null>(null)
const refund = ref<RefundReport | null>(null)
const payroll = ref<PayrollReport | null>(null)
const inventory = ref<InventoryReport | null>(null)
const expenses = ref<ExpenseItem[]>([])
const expensePending = ref(0)

const addOpen = ref(false)
const form = ref({ category: 'MATERIAL', amountYuan: 0, happenedOn: '', vendor: '', remark: '', proofUrl: '' })

const profitTone = computed(() => (store.value?.profitable ? 'ok' : 'bad'))

function q() {
  return month.value ? `?month=${month.value}` : ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (tab.value === 'store') store.value = await request<StoreReport>(`/api/v1/f/finance/reports/store${q()}`)
    if (tab.value === 'consume') consume.value = await request<ConsumeReport>(`/api/v1/f/finance/reports/consume${q()}`)
    if (tab.value === 'trial') trial.value = await request<TrialReport>(`/api/v1/f/finance/reports/trial${q()}`)
    if (tab.value === 'refund') refund.value = await request<RefundReport>(`/api/v1/f/finance/reports/refund${q()}`)
    if (tab.value === 'payroll') payroll.value = await request<PayrollReport>(`/api/v1/f/finance/reports/payroll${q()}`)
    if (tab.value === 'inventory') inventory.value = await request<InventoryReport>('/api/v1/f/finance/reports/inventory')
    if (tab.value === 'expense') {
      const d = await request<{ items: ExpenseItem[]; pending: number }>('/api/v1/f/finance/expenses')
      expenses.value = d.items ?? []
      expensePending.value = d.pending ?? 0
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

async function submitExpense() {
  if (form.value.amountYuan <= 0) {
    ElMessage.warning('金额要大于 0')
    return
  }
  try {
    await request('/api/v1/f/finance/expenses', {
      method: 'POST',
      body: JSON.stringify({
        requestId: 'exp-' + Date.now(),
        category: form.value.category,
        amountFen: Math.round(form.value.amountYuan * 100),
        happenedOn: form.value.happenedOn || undefined,
        vendor: form.value.vendor,
        remark: form.value.remark,
        proofUrl: form.value.proofUrl,
      }),
    })
    ElMessage.success('已提交，等待审批')
    addOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '提交失败')
  }
}

// 提报人不能自批，后端会挡；这里的提示说清原因，别让人以为是权限配错了。
async function decide(row: ExpenseItem, approve: boolean) {
  const verb = approve ? '通过' : '驳回'
  await ElMessageBox.confirm(`${verb}「${row.categoryLabel} ¥${row.amountYuan}」？`, verb)
  try {
    await request(`/api/v1/f/finance/expenses/${row.expenseId}/${approve ? 'approve' : 'reject'}`,
      { method: 'POST' })
    ElMessage.success(verb + '成功')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || (verb + '失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="finance">
    <header class="head">
      <div>
        <h1>财务</h1>
        <p class="label">
          口径：<strong>卖课是预收（负债），耗课才是收入</strong>。
          所以整体业绩表的收入行不等于当月现金流入，库存课那一栏才是还欠着的服务。
        </p>
      </div>
      <div class="acts">
        <el-input v-model="month" placeholder="2026-09" style="width: 120px" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <div class="tabs">
      <button :class="{ on: tab === 'store' }" @click="pickTab('store')">整体业绩</button>
      <button :class="{ on: tab === 'consume' }" @click="pickTab('consume')">耗课收入</button>
      <button :class="{ on: tab === 'trial' }" @click="pickTab('trial')">体验课</button>
      <button :class="{ on: tab === 'refund' }" @click="pickTab('refund')">退费</button>
      <button :class="{ on: tab === 'payroll' }" @click="pickTab('payroll')">老师工资</button>
      <button :class="{ on: tab === 'inventory' }" @click="pickTab('inventory')">库存课</button>
      <button :class="{ on: tab === 'expense' }" @click="pickTab('expense')">
        支出<span v-if="expensePending" class="dot">{{ expensePending }}</span>
      </button>
    </div>

    <!-- 整体业绩 -->
    <section v-if="tab === 'store' && store" class="pane">
      <div class="pl">
        <div class="pl-col">
          <h3>收入</h3>
          <div v-for="r in store.income" :key="r.key" class="pl-row">
            <span>{{ r.label }}</span><b>¥{{ r.amountYuan }}</b>
          </div>
          <div class="pl-row total"><span>收入合计</span><b>¥{{ store.incomeTotalYuan }}</b></div>
        </div>
        <div class="pl-col">
          <h3>支出</h3>
          <div v-for="r in store.expense" :key="r.key" class="pl-row">
            <span>{{ r.label }}</span><b>¥{{ r.amountYuan }}</b>
          </div>
          <div class="pl-row total"><span>支出合计</span><b>¥{{ store.expenseTotalYuan }}</b></div>
        </div>
      </div>
      <div class="profit" :class="profitTone">
        <span>{{ store.month }} 利润</span>
        <b>¥{{ store.profitYuan }}</b>
      </div>
    </section>

    <!-- 耗课收入 -->
    <section v-if="tab === 'consume' && consume" class="pane">
      <p class="sum">{{ consume.month }} · 共 {{ consume.totalLessons }} 节 · 合计 <b>¥{{ consume.totalYuan }}</b></p>
      <el-table :data="consume.rows" stripe>
        <el-table-column prop="customerMask" label="会员" width="140" />
        <el-table-column prop="lessonCount" label="上课数量" width="100" />
        <el-table-column prop="unitPriceYuan" label="单价" width="100" />
        <el-table-column prop="amountYuan" label="金额" width="120" />
        <el-table-column prop="therapistName" label="责任老师" />
      </el-table>
    </section>

    <!-- 体验课 -->
    <section v-if="tab === 'trial' && trial" class="pane">
      <p class="sum">
        {{ trial.month }} · {{ trial.totalCount }} 节 · 转化 {{ trial.convertedCount }} 人
        · 转成交率 <b>{{ Math.round(trial.convertRateX100 / 100) }}%</b>
      </p>
      <h3 class="sub">按渠道</h3>
      <el-table :data="trial.byChannel" stripe size="small">
        <el-table-column prop="channelLabel" label="渠道" width="140" />
        <el-table-column prop="count" label="体验客" width="100" />
        <el-table-column prop="converted" label="转化" width="100" />
        <el-table-column label="转成交率">
          <template #default="{ row }">{{ Math.round(row.convertRateX100 / 100) }}%</template>
        </el-table-column>
      </el-table>
      <h3 class="sub">明细</h3>
      <el-table :data="trial.rows" stripe size="small">
        <el-table-column prop="date" label="日期" width="110" />
        <el-table-column prop="customerMask" label="会员" width="140" />
        <el-table-column prop="therapistName" label="老师" width="110" />
        <el-table-column prop="channelLabel" label="渠道" width="120" />
        <el-table-column prop="amountYuan" label="收入" width="100" />
        <el-table-column label="是否转化">
          <template #default="{ row }">
            <el-tag :type="row.converted ? 'success' : 'info'" size="small">
              {{ row.converted ? '已转化' : '未转化' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 退费 -->
    <section v-if="tab === 'refund' && refund" class="pane">
      <p class="sum">{{ refund.month }} · {{ refund.totalCount }} 笔 · 合计 <b>¥{{ refund.totalYuan }}</b></p>
      <el-table :data="refund.rows" stripe>
        <el-table-column prop="date" label="日期" width="110" />
        <el-table-column prop="customerMask" label="会员" width="140" />
        <el-table-column prop="amountYuan" label="退费金额" width="120" />
        <el-table-column prop="reasonLabel" label="原因" width="130" />
        <el-table-column prop="liableTherapistName" label="责任老师" />
      </el-table>
    </section>

    <!-- 工资 -->
    <section v-if="tab === 'payroll' && payroll" class="pane">
      <p class="sum">{{ payroll.month }} · 合计 <b>¥{{ payroll.totalYuan }}</b></p>
      <el-table :data="payroll.rows" stripe>
        <el-table-column prop="therapistName" label="姓名" width="110" />
        <el-table-column prop="attendanceDays" label="出勤" width="80" />
        <el-table-column prop="lessonCount" label="上课" width="80" />
        <el-table-column prop="lessonFeeYuan" label="课时费" width="110" />
        <el-table-column prop="salesYuan" label="销售额" width="120" />
        <el-table-column label="提点" width="80">
          <template #default="{ row }">{{ Math.round(row.saleRateX100 / 100) }}%</template>
        </el-table-column>
        <el-table-column prop="saleCommissionYuan" label="销售提成" width="110" />
        <el-table-column prop="baseSalaryYuan" label="底薪" width="100" />
        <el-table-column prop="totalYuan" label="合计" />
      </el-table>
    </section>

    <!-- 库存课 -->
    <section v-if="tab === 'inventory' && inventory" class="pane">
      <p class="sum">
        截至 {{ inventory.asOf }} · 未耗 {{ inventory.totalSessions }} 节 ·
        负债 <b>¥{{ inventory.totalValueYuan }}</b>
        <span class="warn">（其中 30 天内到期 ¥{{ inventory.expiringValueYuan }}）</span>
      </p>
      <el-table :data="inventory.rows" stripe>
        <el-table-column prop="customerMask" label="会员" width="140" />
        <el-table-column prop="title" label="课包" />
        <el-table-column prop="remainingSessions" label="剩余" width="80" />
        <el-table-column prop="unitPriceYuan" label="单价" width="100" />
        <el-table-column prop="valueYuan" label="价值" width="110" />
        <el-table-column label="有效期" width="150">
          <template #default="{ row }">
            <span :class="{ warn: row.expiringSoon }">{{ row.expireOn ?? '不过期' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 支出 -->
    <section v-if="tab === 'expense'" class="pane">
      <div class="sub-head">
        <p class="sum">仅审批通过的计入成本</p>
        <el-button type="primary" @click="addOpen = true">提报支出</el-button>
      </div>
      <el-table :data="expenses" stripe>
        <el-table-column prop="happenedOn" label="日期" width="110" />
        <el-table-column prop="categoryLabel" label="类目" width="110" />
        <el-table-column prop="amountYuan" label="金额" width="110" />
        <el-table-column prop="vendor" label="供应商" width="140" />
        <el-table-column prop="submitterName" label="提报人" width="100" />
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
              <el-button size="small" type="primary" @click="decide(row, true)">通过</el-button>
              <el-button size="small" @click="decide(row, false)">驳回</el-button>
            </template>
            <span v-else class="muted">{{ row.rejectReason ?? '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="addOpen" title="提报支出" width="460px">
      <el-form label-width="80px">
        <el-form-item label="类目">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in CATEGORIES" :key="c.v" :label="c.l" :value="c.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额 ¥">
          <el-input-number v-model="form.amountYuan" :min="0" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="发生日">
          <el-date-picker v-model="form.happenedOn" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="供应商"><el-input v-model="form.vendor" /></el-form-item>
        <el-form-item label="凭证"><el-input v-model="form.proofUrl" placeholder="图片链接" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <p class="hint">提报后需由他人审批。自己提的自己批不了——那样凭证审批只是走形式。</p>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" @click="submitExpense">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.finance { padding: 24px; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.label { color: #8b948f; font-size: 13px; margin: 4px 0 0; max-width: 640px; line-height: 1.6; }
.acts { display: flex; gap: 8px; flex-shrink: 0; }

.tabs { display: flex; gap: 6px; flex-wrap: wrap; margin: 16px 0; }
.tabs button {
  padding: 7px 16px; border: 1px solid #dfe5e3; background: #fff; border-radius: 999px;
  font-size: 13.5px; cursor: pointer; color: #3d4d46;
}
.tabs button.on { background: #1e5c4a; border-color: #1e5c4a; color: #fff; }
.dot { margin-left: 6px; background: #c2760c; color: #fff; border-radius: 999px; padding: 0 6px; font-size: 11px; }

.pane { margin-top: 4px; }
.sum { color: #3d4d46; font-size: 14px; margin: 0 0 12px; }
.sum b { font-family: ui-monospace, Menlo, monospace; }
.sub { font-size: 14px; margin: 20px 0 8px; }
.sub-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }

.pl { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; }
.pl-col { background: #fff; border: 1px solid #eef2f0; border-radius: 10px; padding: 18px 20px; }
.pl-col h3 { margin: 0 0 12px; font-size: 14px; }
.pl-row { display: flex; justify-content: space-between; padding: 7px 0; font-size: 14px; border-bottom: 1px solid #f4f6f5; }
.pl-row:last-child { border-bottom: none; }
.pl-row b { font-family: ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
.pl-row.total { border-top: 2px solid #e3e9e6; margin-top: 6px; padding-top: 12px; font-weight: 600; border-bottom: none; }

.profit { margin-top: 20px; padding: 18px 22px; border-radius: 10px; display: flex; justify-content: space-between; align-items: center; font-size: 16px; }
.profit b { font-family: ui-monospace, Menlo, monospace; font-size: 22px; }
.profit.ok { background: #e6f2ec; color: #1e5c4a; }
.profit.bad { background: #fdecea; color: #b3261e; }

.warn { color: #c2760c; }
.muted { color: #8b948f; }
.hint { color: #8b948f; font-size: 12px; line-height: 1.7; margin: 0 0 8px; }
</style>
