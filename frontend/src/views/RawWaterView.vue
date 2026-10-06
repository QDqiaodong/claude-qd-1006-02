<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import router from '../router'

const props = defineProps({ role: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')

const canCreate = computed(() => props.role === '化验员' || props.role === '管理员')
// 界面与服务端同一口径：仅管理员可人工重判
const canReclassify = computed(() => props.role === '管理员')

const statuses = ['合格', '预警', '超标']
const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())

// ---------------- 人工重判（仅管理员，必填原因，请求级幂等） ----------------
const rcDialog = ref(false)
const rcSaving = ref(false)
const rcForm = ref({ id: null, sourceName: '', fromStatus: '', target: '', reason: '', requestId: '' })
const operator = ref(localStorage.getItem('rawwater-operator') || '')

// ---------------- 裁决日志 ----------------
const logDialog = ref(false)
const logLoading = ref(false)
const logRows = ref([])
const logSample = ref('')

function blank() {
  return {
    sourceName: '',
    sampleAt: '',
    ph: '',
    turbidityNtu: '',
    chlorineMgL: '',
    waterSample: null
  }
}

function newRequestId() {
  return (window.crypto && crypto.randomUUID)
    ? crypto.randomUUID()
    : 'req-' + Date.now() + '-' + Math.random().toString(36).slice(2)
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listRawWaters(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() {
  form.value = blank(); dialog.value = true; tip.value = ''; error.value = ''
}

async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      sourceName: form.value.sourceName,
      sampleAt: form.value.sampleAt ? form.value.sampleAt : null,
      waterSample: {
        ph: Number(form.value.ph),
        turbidityNtu: Number(form.value.turbidityNtu),
        chlorineMgL: Number(form.value.chlorineMgL)
      }
    }
    await api.createRawWater(d)
    tip.value = `原水「${d.sourceName}」已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

// 打开重判对话框：每次发起生成一个请求标识（连点 / 重试都复用它，服务端只落一份日志）
function openReclassify(row, target) {
  if (!canReclassify.value) { error.value = '仅管理员可执行人工重判'; return }
  tip.value = ''; error.value = ''
  rcForm.value = {
    id: row.id,
    sourceName: row.sourceName,
    fromStatus: row.status,
    target,
    reason: '',
    requestId: newRequestId()
  }
  rcDialog.value = true
}

async function submitReclassify() {
  if (rcSaving.value) return
  if (!operator.value || !operator.value.trim()) { error.value = '请填写操作人'; return }
  if (!rcForm.value.reason || !rcForm.value.reason.trim()) { error.value = '人工重判必须填写操作原因'; return }
  rcSaving.value = true; error.value = ''
  try {
    await api.reclassifyRawWater(rcForm.value.id, {
      status: rcForm.value.target,
      fromStatus: rcForm.value.fromStatus,
      operator: operator.value.trim(),
      role: props.role,
      reason: rcForm.value.reason.trim(),
      requestId: rcForm.value.requestId
    })
    localStorage.setItem('rawwater-operator', operator.value.trim())
    tip.value = `${rcForm.value.sourceName} 已重判为「${rcForm.value.target}」`
    rcDialog.value = false
    await load()
  } catch (e) {
    // 裁决冲突 / 越权等：展示服务端提示并刷新，让页面与服务端结论保持一致
    error.value = e.message
    await load()
  } finally { rcSaving.value = false }
}

async function openLogs(row) {
  logSample.value = `${row.sourceName}（记录 #${row.id}）`
  logDialog.value = true
  logLoading.value = true
  try {
    logRows.value = await api.listRawWaterJudgmentLogs(row.id) || []
  } catch (e) { error.value = e.message } finally { logLoading.value = false }
}

// 超标记录 → 跳转到处置闭环发起联动处置（携带记录 id 预选）
function goDisposal(row) {
  router.push({ path: '/disposal', query: { rawWaterId: row.id } })
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">原水监测 · 取水口水质</span>
        <span class="panel-desc">建档硬约束：pH 6.5~8.5、浊度 &lt; 3 NTU、余氯 0.3~4 mg/L，否则拒绝建档；人工重判仅管理员，需填写原因并留痕</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">记录数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">合格</div><div class="v">{{ rows.filter(r => r.status === '合格').length }}</div></div>
        <div class="stat"><div class="k">预警</div><div class="v">{{ rows.filter(r => r.status === '预警').length }}</div></div>
        <div class="stat"><div class="k">超标</div><div class="v">{{ rows.filter(r => r.status === '超标').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" :disabled="!canCreate" @click="openCreate">
          {{ canCreate ? '新建取样' : '仅化验员/管理员可建档' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="sourceName" label="水源" min-width="130" />
        <el-table-column prop="sampleAt" label="取样时间" min-width="170" />
        <el-table-column label="pH" width="90">
          <template #default="{ row }">{{ row.waterSample?.ph }}</template>
        </el-table-column>
        <el-table-column label="浊度(NTU)" width="100">
          <template #default="{ row }">{{ row.waterSample?.turbidityNtu }}</template>
        </el-table-column>
        <el-table-column label="余氯(mg/L)" width="100">
          <template #default="{ row }">{{ row.waterSample?.chlorineMgL }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '合格' ? 'success' : row.status === '预警' ? 'warning' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" min-width="170" />
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === '超标'" size="small" type="warning"
              :disabled="!(role === '运行工' || role === '管理员')" @click="goDisposal(row)">发起处置</el-button>
            <el-button size="small" type="danger" plain :disabled="!canReclassify || row.status === '超标'"
              @click="openReclassify(row, '超标')">判超标</el-button>
            <el-button size="small" :disabled="!canReclassify || row.status === '合格'"
              @click="openReclassify(row, '合格')">复位合格</el-button>
            <el-button size="small" text type="info" @click="openLogs(row)">裁决日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建原水取样" width="600px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="水源名称" class="full">
          <el-input v-model="form.sourceName" placeholder="如 长江取水泵房 / 一号取水口" />
        </el-form-item>
        <el-form-item label="取样时间" class="full">
          <el-date-picker v-model="form.sampleAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空取当前时间" />
        </el-form-item>
        <div class="triple">
          <el-form-item label="pH"><el-input v-model="form.ph" type="number" step="0.01" /></el-form-item>
          <el-form-item label="浊度(NTU)"><el-input v-model="form.turbidityNtu" type="number" step="0.01" /></el-form-item>
          <el-form-item label="余氯(mg/L)"><el-input v-model="form.chlorineMgL" type="number" step="0.01" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rcDialog" title="人工重判水质结论" width="560px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon
        :title="`仅管理员可重判；提交后生成不可覆盖的裁决日志（操作者 / 时间 / 前后结论 / 原因）`" />
      <el-form label-width="120px" class="dialog-form" style="margin-top: 14px">
        <el-form-item label="水源" class="full">
          <span>{{ rcForm.sourceName }}（记录 #{{ rcForm.id }}）</span>
        </el-form-item>
        <el-form-item label="结论变更" class="full">
          <el-tag>{{ rcForm.fromStatus }}</el-tag>
          <span style="margin: 0 8px">→</span>
          <el-tag :type="rcForm.target === '超标' ? 'danger' : 'success'">{{ rcForm.target }}</el-tag>
        </el-form-item>
        <el-form-item label="操作人" class="full">
          <el-input v-model="operator" placeholder="管理员姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="操作原因" class="full" required>
          <el-input v-model="rcForm.reason" type="textarea" :rows="3" maxlength="255"
            show-word-limit placeholder="必填：说明重判依据（如复检结果、异常说明）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rcDialog = false">取消</el-button>
        <el-button type="primary" :loading="rcSaving" :disabled="rcSaving" @click="submitReclassify">确认重判</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logDialog" :title="`裁决日志 · ${logSample}`" width="760px">
      <el-table :data="logRows" v-loading="logLoading" border stripe size="small">
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <el-table-column prop="operatorName" label="操作人" width="100" />
        <el-table-column prop="action" label="动作" width="90" />
        <el-table-column label="结论变更" width="130">
          <template #default="{ row }">{{ row.fromStatus }} → {{ row.toStatus }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="操作原因" min-width="220" show-overflow-tooltip />
      </el-table>
      <div v-if="!logLoading && logRows.length === 0" class="empty-tip">暂无人工重判记录</div>
    </el-dialog>
  </div>
</template>
