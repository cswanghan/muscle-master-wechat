<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { getToken, request } from '../api'

type StoreItem = { storeId: string; code: string; name: string; status: number }
type TherapistItem = {
  therapistId: string
  employeeNo: string
  name: string
  homeStoreId: string
  level: string
  intro?: string
  status: number
}
type ProjectItem = {
  projectId: string
  code: string
  name: string
  durationMinutes: number
  bufferMinutes: number
  priceFen: number
  description?: string
  status: number
}
type TemplateItem = {
  templateId: string
  therapistId: string
  storeId: string
  weekday: number
  startTime: string
  endTime: string
  effectiveFrom: string
  effectiveTo?: string | null
  status: number
}

const stores = ref<StoreItem[]>([])
const therapists = ref<TherapistItem[]>([])
const projects = ref<ProjectItem[]>([])
const templates = ref<TemplateItem[]>([])
const loading = ref(false)
const error = ref('')
const activeTab = ref('stores')

const storeDlg = ref(false)
const therapistDlg = ref(false)
const projectDlg = ref(false)
const templateDlg = ref(false)
const editingStoreId = ref('')
const editingTherapistId = ref('')
const editingProjectId = ref('')
const editingTemplateId = ref('')

const storeForm = reactive({ code: '', name: '', status: 1 })
const therapistForm = reactive({
  employeeNo: '',
  name: '',
  homeStoreId: '',
  level: 'JUNIOR',
  intro: '',
  status: 1,
})
const projectForm = reactive({
  code: '',
  name: '',
  durationMinutes: 60,
  bufferMinutes: 15,
  priceFen: 19800,
  description: '',
  status: 1,
})
const templateForm = reactive({
  therapistId: '',
  storeId: '',
  weekday: 1,
  startTime: '10:00',
  endTime: '22:00',
  effectiveFrom: '2026-01-01',
  effectiveTo: '',
  status: 1,
})

function fenYuan(fen: number) {
  return (fen / 100).toFixed(0)
}

function weekdayLabel(n: number) {
  return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][n] ?? String(n)
}

async function loadAll() {
  if (!getToken()) {
    error.value = '请先登录超管'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [storePage, therapistPage, projectPage, templatePage] = await Promise.all([
      request<{ items: StoreItem[] }>('/api/v1/a/stores'),
      request<{ items: TherapistItem[] }>('/api/v1/a/therapists'),
      request<{ items: ProjectItem[] }>('/api/v1/a/projects'),
      request<{ items: TemplateItem[] }>('/api/v1/a/schedule-templates'),
    ])
    stores.value = storePage.items
    therapists.value = therapistPage.items
    projects.value = projectPage.items
    templates.value = templatePage.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function openStore(row?: StoreItem) {
  editingStoreId.value = row?.storeId ?? ''
  storeForm.code = row?.code ?? ''
  storeForm.name = row?.name ?? ''
  storeForm.status = row?.status ?? 1
  storeDlg.value = true
}

function openTherapist(row?: TherapistItem) {
  editingTherapistId.value = row?.therapistId ?? ''
  therapistForm.employeeNo = row?.employeeNo ?? ''
  therapistForm.name = row?.name ?? ''
  therapistForm.homeStoreId = row?.homeStoreId ?? stores.value[0]?.storeId ?? ''
  therapistForm.level = row?.level ?? 'JUNIOR'
  therapistForm.intro = row?.intro ?? ''
  therapistForm.status = row?.status ?? 1
  therapistDlg.value = true
}

function openProject(row?: ProjectItem) {
  editingProjectId.value = row?.projectId ?? ''
  projectForm.code = row?.code ?? ''
  projectForm.name = row?.name ?? ''
  projectForm.durationMinutes = row?.durationMinutes ?? 60
  projectForm.bufferMinutes = row?.bufferMinutes ?? 15
  projectForm.priceFen = row?.priceFen ?? 19800
  projectForm.description = row?.description ?? ''
  projectForm.status = row?.status ?? 1
  projectDlg.value = true
}

function openTemplate(row?: TemplateItem) {
  editingTemplateId.value = row?.templateId ?? ''
  templateForm.therapistId = row?.therapistId ?? therapists.value[0]?.therapistId ?? ''
  templateForm.storeId = row?.storeId ?? stores.value[0]?.storeId ?? ''
  templateForm.weekday = row?.weekday ?? 1
  templateForm.startTime = row?.startTime ?? '10:00'
  templateForm.endTime = row?.endTime ?? '22:00'
  templateForm.effectiveFrom = row?.effectiveFrom ?? '2026-01-01'
  templateForm.effectiveTo = row?.effectiveTo ?? ''
  templateForm.status = row?.status ?? 1
  templateDlg.value = true
}

async function saveStore() {
  try {
    if (editingStoreId.value) {
      await request(`/api/v1/a/stores/${editingStoreId.value}`, {
        method: 'PUT',
        body: JSON.stringify({ name: storeForm.name, status: storeForm.status }),
      })
    } else {
      await request('/api/v1/a/stores', {
        method: 'POST',
        body: JSON.stringify(storeForm),
      })
    }
    storeDlg.value = false
    await loadAll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function saveTherapist() {
  try {
    const path = editingTherapistId.value
      ? `/api/v1/a/therapists/${editingTherapistId.value}`
      : '/api/v1/a/therapists'
    await request(path, {
      method: editingTherapistId.value ? 'PUT' : 'POST',
      body: JSON.stringify(therapistForm),
    })
    therapistDlg.value = false
    await loadAll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function saveProject() {
  try {
    const path = editingProjectId.value
      ? `/api/v1/a/projects/${editingProjectId.value}`
      : '/api/v1/a/projects'
    await request(path, {
      method: editingProjectId.value ? 'PUT' : 'POST',
      body: JSON.stringify(projectForm),
    })
    projectDlg.value = false
    await loadAll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function saveTemplate() {
  try {
    const path = editingTemplateId.value
      ? `/api/v1/a/schedule-templates/${editingTemplateId.value}`
      : '/api/v1/a/schedule-templates'
    await request(path, {
      method: editingTemplateId.value ? 'PUT' : 'POST',
      body: JSON.stringify(templateForm),
    })
    templateDlg.value = false
    await loadAll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function remove(kind: 'stores' | 'therapists' | 'projects' | 'schedule-templates', id: string) {
  try {
    await ElMessageBox.confirm('删除后编码/工号不可复用', '确认删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await request(`/api/v1/a/${kind}/${id}`, { method: 'DELETE' })
    await loadAll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(loadAll)
</script>

<template>
  <div id="catalog-page" class="catalog">
    <el-card class="health-card" shadow="never">
      <template #header>
        <div class="card-head">
          <h1>目录 CRUD</h1>
          <el-button type="primary" :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </template>
      <el-alert
        v-if="error"
        title="目录加载失败"
        type="error"
        :description="error"
        show-icon
        :closable="false"
      />
      <p class="label">@StoreScoped · /a/stores · /a/therapists · /a/projects · /a/schedule-templates</p>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="门店" name="stores">
          <div class="toolbar">
            <el-button id="store-create-btn" type="primary" @click="openStore()">新建门店</el-button>
          </div>
          <el-table id="store-table" :data="stores" stripe>
            <el-table-column prop="code" label="编码" width="110" />
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">{{ row.status === 1 ? '营业' : '停业' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button size="small" @click="openStore(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="remove('stores', row.storeId)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="技师" name="therapists">
          <div class="toolbar">
            <el-button type="primary" @click="openTherapist()">新建技师</el-button>
          </div>
          <el-table id="therapist-table" :data="therapists" stripe>
            <el-table-column prop="employeeNo" label="工号" width="90" />
            <el-table-column prop="name" label="姓名" width="100" />
            <el-table-column prop="level" label="等级" width="100" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">{{ row.status === 1 ? '在职' : '停用' }}</template>
            </el-table-column>
            <el-table-column prop="intro" label="简介" />
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button size="small" @click="openTherapist(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="remove('therapists', row.therapistId)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="项目" name="projects">
          <div class="toolbar">
            <el-button type="primary" @click="openProject()">新建项目</el-button>
          </div>
          <el-table id="project-table" :data="projects" stripe>
            <el-table-column prop="code" label="编码" width="80" />
            <el-table-column prop="name" label="名称" />
            <el-table-column label="时长" width="120">
              <template #default="{ row }">{{ row.durationMinutes }}+{{ row.bufferMinutes }} 分</template>
            </el-table-column>
            <el-table-column label="价格" width="90">
              <template #default="{ row }">¥{{ fenYuan(row.priceFen) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">{{ row.status === 1 ? '上架' : '下架' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button size="small" @click="openProject(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="remove('projects', row.projectId)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="排班模板" name="templates">
          <div class="toolbar">
            <el-button type="primary" @click="openTemplate()">新建模板</el-button>
          </div>
          <el-table id="template-table" :data="templates" stripe max-height="360">
            <el-table-column label="技师" width="140">
              <template #default="{ row }">
                {{ therapists.find((t) => t.therapistId === row.therapistId)?.name ?? row.therapistId }}
              </template>
            </el-table-column>
            <el-table-column label="星期" width="80">
              <template #default="{ row }">{{ weekdayLabel(row.weekday) }}</template>
            </el-table-column>
            <el-table-column label="时段" width="140">
              <template #default="{ row }">{{ row.startTime }}–{{ row.endTime }}</template>
            </el-table-column>
            <el-table-column prop="effectiveFrom" label="生效" />
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button size="small" @click="openTemplate(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="remove('schedule-templates', row.templateId)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="storeDlg" :title="editingStoreId ? '编辑门店' : '新建门店'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="storeForm.code" :disabled="!!editingStoreId" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="storeForm.name" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveStore">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="therapistDlg" :title="editingTherapistId ? '编辑技师' : '新建技师'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="工号"><el-input v-model="therapistForm.employeeNo" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="therapistForm.name" /></el-form-item>
        <el-form-item label="归属店">
          <el-select v-model="therapistForm.homeStoreId" style="width: 100%">
            <el-option v-for="s in stores" :key="s.storeId" :label="s.name" :value="s.storeId" />
          </el-select>
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="therapistForm.level" style="width: 100%">
            <el-option label="JUNIOR" value="JUNIOR" />
            <el-option label="MIDDLE" value="MIDDLE" />
            <el-option label="SENIOR" value="SENIOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="简介"><el-input v-model="therapistForm.intro" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveTherapist">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="projectDlg" :title="editingProjectId ? '编辑项目' : '新建项目'" width="460px">
      <el-form label-width="100px">
        <el-form-item label="编码"><el-input v-model="projectForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="projectForm.name" /></el-form-item>
        <el-form-item label="时长(分)"><el-input-number v-model="projectForm.durationMinutes" :min="15" :step="15" /></el-form-item>
        <el-form-item label="缓冲(分)"><el-input-number v-model="projectForm.bufferMinutes" :min="1" :max="15" /></el-form-item>
        <el-form-item label="价格(分)"><el-input-number v-model="projectForm.priceFen" :min="0" :step="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="templateDlg" :title="editingTemplateId ? '编辑模板' : '新建模板'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="技师">
          <el-select v-model="templateForm.therapistId" style="width: 100%">
            <el-option v-for="t in therapists" :key="t.therapistId" :label="t.name" :value="t.therapistId" />
          </el-select>
        </el-form-item>
        <el-form-item label="门店">
          <el-select v-model="templateForm.storeId" style="width: 100%">
            <el-option v-for="s in stores" :key="s.storeId" :label="s.name" :value="s.storeId" />
          </el-select>
        </el-form-item>
        <el-form-item label="星期">
          <el-input-number v-model="templateForm.weekday" :min="1" :max="7" />
        </el-form-item>
        <el-form-item label="开始"><el-input v-model="templateForm.startTime" /></el-form-item>
        <el-form-item label="结束"><el-input v-model="templateForm.endTime" /></el-form-item>
        <el-form-item label="生效"><el-input v-model="templateForm.effectiveFrom" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
