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
const canReclassify = computed(() => props.role === '管理员')

const statuses = ['合格', '预警', '超标']
const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())

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

// ==================== 人工重判（仅管理员，必填原因，一次裁决一条日志） ====================

// 当前操作者：与加药复核同一口径，持久化到 localStorage，刷新不丢
const operator = ref(localStorage.getItem('dosing-operator') || '')
const rcDialog = ref(false)
const rcSaving = ref(false)
const rcError = ref('')
const rcForm = ref({ id: null, sourceName: '', fromStatus: '', toStatus: '', reason: '' })
// 本次裁决的幂等令牌：dialog 打开时生成一次，提交期间连点 / 重放都复用它
let rcRequestId = ''

function newRequestId() {
  if (window.crypto?.randomUUID) return `rc-${window.crypto.randomUUID()}`
  return `rc-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function openReclassify(row, target) {
  if (!canReclassify.value) return
  rcError.value = ''; error.value = ''; tip.value = ''
  rcForm.value = {
    id: row.id,
    sourceName: row.sourceName,
    fromStatus: row.status,
    toStatus: target,
    reason: ''
  }
  rcRequestId = newRequestId()
  rcDialog.value = true
}

async function submitReclassify() {
  // 防快速连点：提交中直接忽略后续点击（后端另有 requestId 幂等 + 行锁兜底）
  if (rcSaving.value) return
  const op = (operator.value || '').trim()
  const reason = (rcForm.value.reason || '').trim()
  if (!op) { rcError.value = '请填写操作者'; return }
  if (!reason) { rcError.value = '人工重判必须填写操作原因'; return }
  persistOperator()
  rcSaving.value = true; rcError.value = ''
  try {
    await api.reclassifyRawWater(rcForm.value.id, {
      status: rcForm.value.toStatus,
      operator: op,
      reason,
      role: props.role,
      // 携带重判前结论：并发时若已被他人裁决，后端返回冲突而不是静默覆盖
      expectedStatus: rcForm.value.fromStatus,
      requestId: rcRequestId
    })
    tip.value = `${rcForm.value.sourceName} 已重判为「${rcForm.value.toStatus}」，裁决日志已留存`
    rcDialog.value = false
    if (logsVisible.value === rcForm.value.id) await loadLogs(rcForm.value.id)
    await load()
  } catch (e) { rcError.value = e.message } finally { rcSaving.value = false }
}

function persistOperator() {
  localStorage.setItem('dosing-operator', operator.value || '')
}

// 裁决日志追溯
const logsVisible = ref(null)
const logsLoading = ref(false)
const logsData = ref(null)
const logsError = ref('')

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

async function toggleLogs(row) {
  if (logsVisible.value === row.id) {
    logsVisible.value = null
    logsData.value = null
    return
  }
  logsVisible.value = row.id
  await loadLogs(row.id)
}

async function loadLogs(id) {
  logsLoading.value = true; logsError.value = ''
  try {
    logsData.value = await api.getRawWaterReclassifyLogs(id)
  } catch (e) { logsError.value = e.message } finally { logsLoading.value = false }
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
        <span class="panel-desc">建档硬约束：pH 6.5~8.5、浊度 &lt; 3 NTU、余氯 0.3~4 mg/L，否则拒绝建档</span>
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
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === '超标'" size="small" type="warning"
              :disabled="!(role === '运行工' || role === '管理员')" @click="goDisposal(row)">发起处置</el-button>
            <el-button size="small" type="danger" plain :disabled="!canReclassify" @click="openReclassify(row, '超标')">判超标</el-button>
            <el-button size="small" :disabled="!canReclassify" @click="openReclassify(row, '合格')">复位合格</el-button>
            <el-button size="small" text @click="toggleLogs(row)">裁决日志</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="logsVisible" class="rc-logs" v-loading="logsLoading">
        <template v-if="logsData">
          <div class="rc-logs-head">
            「{{ logsData.sourceName }}」人工重判裁决链（{{ logsData.logs.length }} 条，只增不改、逐条可追溯）
          </div>
          <div v-if="logsError" class="err">{{ logsError }}</div>
          <el-empty v-if="!logsData.logs.length" description="暂无人工重判记录，当前结论为自动判级 / 建档结论" :image-size="60" />
          <el-timeline v-else>
            <el-timeline-item v-for="l in logsData.logs" :key="l.id" :timestamp="fmt(l.createdAt)" placement="top">
              <div class="rc-log-line">
                <el-tag size="small">{{ l.fromStatus }}</el-tag>
                <span class="rc-arrow">→</span>
                <el-tag size="small" :type="l.toStatus === '超标' ? 'danger' : 'success'">{{ l.toStatus }}</el-tag>
                <span class="rc-op">操作者：{{ l.operatorName }}</span>
              </div>
              <div class="rc-reason">原因：{{ l.reason }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
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

    <!-- 人工重判：仅管理员可打开；操作者与操作原因必填 -->
    <el-dialog v-model="rcDialog" title="人工重判水质结论" width="560px">
      <el-form label-width="100px">
        <el-form-item label="样本">
          <span>{{ rcForm.sourceName }}（#{{ rcForm.id }}）</span>
        </el-form-item>
        <el-form-item label="重判前结论">
          <el-tag size="small">{{ rcForm.fromStatus }}</el-tag>
          <span class="rc-arrow">→</span>
          <el-tag size="small" :type="rcForm.toStatus === '超标' ? 'danger' : 'success'">{{ rcForm.toStatus }}</el-tag>
        </el-form-item>
        <el-form-item label="操作者" required>
          <el-input v-model="operator" placeholder="管理员姓名" @input="persistOperator" />
        </el-form-item>
        <el-form-item label="操作原因" required>
          <el-input v-model="rcForm.reason" type="textarea" :rows="3" maxlength="255" show-word-limit
            placeholder="必须填写重判原因，将随裁决日志永久留存" />
        </el-form-item>
      </el-form>
      <div v-if="rcError" class="err">{{ rcError }}</div>
      <template #footer>
        <el-button @click="rcDialog = false">取消</el-button>
        <el-button type="primary" :loading="rcSaving" @click="submitReclassify">确认重判</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rc-logs {
  margin-top: 12px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 6px;
  background: var(--el-fill-color-lighter, #fafafa);
}
.rc-logs-head {
  font-weight: 600;
  margin-bottom: 8px;
}
.rc-log-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rc-arrow {
  color: #909399;
}
.rc-op {
  margin-left: 8px;
  color: #606266;
  font-size: 13px;
}
.rc-reason {
  margin-top: 4px;
  color: #303133;
  font-size: 13px;
}
</style>
