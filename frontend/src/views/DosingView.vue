<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const props = defineProps({ role: String })

// ------------------------------------------------------------------
// 加药批次记录与异常复核：待确认 → 已确认；待确认 → 异常待复核 → 已确认 / 已退回。
// 所有状态以后端为准：每次操作后端返回最新详情，刷新 / 重进都从这里重新拉取。
// 页面按钮的权限口径与后端校验一致：
//   登记/修改 = 运行工或管理员（修改仅限本人提交且待确认）；确认/标记异常/复核/全部异常 = 管理员。
// ------------------------------------------------------------------

const isAdmin = computed(() => props.role === '管理员')
const canRegister = computed(() => props.role === '运行工' || props.role === '管理员')

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const targetFilter = ref('')
const statusFilter = ref('')
const timeRange = ref(null)

// 当前值班人：登记 / 修改 / 复核的操作人，持久化到 localStorage，刷新不丢
const operator = ref(localStorage.getItem('dosing-operator') || '')

const statuses = ['待确认', '已确认', '异常待复核', '已退回']

// 工艺配置（登记表单的可选项 = 当前启用项）
const configItems = ref([])
const chemOptions = computed(() => configItems.value.filter(i => i.itemType === '药剂' && i.enabled).map(i => i.itemName))
const targetOptions = computed(() => configItems.value.filter(i => i.itemType === '目标工段' && i.enabled).map(i => i.itemName))
// 追溯筛选：当前配置工段 + 历史记录中出现过的工段（含配置已变更的）
const targetFilterOptions = computed(() =>
  [...new Set([...targetOptions.value, ...rows.value.map(r => r.targetSection)])])

const stats = computed(() => ({
  pending: rows.value.filter(r => r.status === '待确认').length,
  confirmed: rows.value.filter(r => r.status === '已确认').length,
  anomaly: rows.value.filter(r => r.status === '异常待复核').length,
  returned: rows.value.filter(r => r.status === '已退回').length
}))

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

function statusTag(s) {
  return s === '已确认' ? 'success' : s === '待确认' ? 'warning' : s === '异常待复核' ? 'danger' : 'info'
}

function persistOperator() {
  localStorage.setItem('dosing-operator', operator.value || '')
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const p = {}
    if (targetFilter.value) p.targetSection = targetFilter.value
    if (statusFilter.value) p.status = statusFilter.value
    if (timeRange.value && timeRange.value[0]) {
      p.executeFrom = timeRange.value[0]
      p.executeTo = timeRange.value[1]
    }
    rows.value = await api.listDosingRecords(p) || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadConfig() {
  try {
    configItems.value = await api.listProcessConfig() || []
  } catch (e) { /* 配置加载失败不阻断列表 */ }
}

// 修改按钮口径与后端一致：本人提交 + 待确认 + 运行工/管理员
function canEditRow(row) {
  return canRegister.value && row.status === '待确认' && row.operatorName === operator.value.trim()
}

function requireOperator(errRef) {
  if (!operator.value || !operator.value.trim()) {
    errRef.value = '请先在右上角填写当前值班人'
    return false
  }
  persistOperator()
  return true
}

// ==================== 登记 ====================

const createDialog = ref(false)
const createError = ref('')
const saving = ref(false)
const createForm = ref(blank())

function blank() {
  return { chemName: '', doseMgPerL: null, targetSection: '', executeAt: '', operator: '', note: '' }
}

async function openCreate() {
  createError.value = ''
  createForm.value = { ...blank(), operator: operator.value }
  await loadConfig()
  createForm.value.chemName = chemOptions.value[0] || ''
  createForm.value.targetSection = targetOptions.value[0] || ''
  createDialog.value = true
}

function validateForm(f) {
  if (!f.chemName) return '请选择药剂'
  if (!f.doseMgPerL || f.doseMgPerL <= 0) return '投加量必须大于 0（mg/L）'
  if (!f.targetSection) return '请选择目标工段'
  if (!f.executeAt) return '请选择执行时间'
  if (!f.operator || !f.operator.trim()) return '请填写操作者'
  return ''
}

async function submitCreate() {
  const f = createForm.value
  const invalid = validateForm(f)
  if (invalid) { createError.value = invalid; return }
  saving.value = true; createError.value = ''
  try {
    const detail = await api.createDosingRecord({
      chemName: f.chemName, doseMgPerL: f.doseMgPerL, targetSection: f.targetSection,
      executeAt: f.executeAt, operator: f.operator.trim(), note: f.note, role: props.role
    })
    operator.value = f.operator.trim(); persistOperator()
    tip.value = `批次 #${detail.id} 已登记（${detail.targetSection} · ${fmt(detail.executeAt)}），待管理员核对回执确认`
    createDialog.value = false
    await load()
  } catch (e) {
    // 重复登记 / 配置不符：后端返回的明确原因直接展示，并刷新列表让已存在批次可见
    createError.value = e.message
    await load()
  } finally { saving.value = false }
}

// ==================== 修改（本人 + 待确认） ====================

const editDialog = ref(false)
const editError = ref('')
const editSaving = ref(false)
const editForm = ref(blank())
const editId = ref(null)

async function openEdit(row) {
  editError.value = ''
  editId.value = row.id
  editForm.value = {
    chemName: row.chemName, doseMgPerL: row.doseMgPerL, targetSection: row.targetSection,
    executeAt: row.executeAt, operator: row.operatorName, note: row.note || ''
  }
  await loadConfig()
  editDialog.value = true
}

async function submitEdit() {
  const f = editForm.value
  const invalid = validateForm(f)
  if (invalid) { editError.value = invalid; return }
  editSaving.value = true; editError.value = ''
  try {
    const detail = await api.updateDosingRecord(editId.value, {
      chemName: f.chemName, doseMgPerL: f.doseMgPerL, targetSection: f.targetSection,
      executeAt: f.executeAt, operator: f.operator.trim(), note: f.note, role: props.role
    })
    tip.value = `批次 #${detail.id} 已更新`
    editDialog.value = false
    await load()
  } catch (e) {
    editError.value = e.message
    await load()
  } finally { editSaving.value = false }
}

// ==================== 详情 ====================

const drawer = ref(false)
const detail = ref(null)
const detailError = ref('')

async function openDetail(row) {
  detailError.value = ''
  try {
    detail.value = await api.getDosingRecord(row.id)
    drawer.value = true
  } catch (e) { error.value = e.message }
}

async function refreshDetail() {
  detail.value = await api.getDosingRecord(detail.value.id)
  await load()
}

// ==================== 确认 / 标记异常 / 复核退回（管理员） ====================

const acting = ref(false)
const confirmDialog = ref(false)
const confirmNote = ref('')
const confirmError = ref('')

function openConfirm() {
  confirmNote.value = ''
  confirmError.value = ''
  confirmDialog.value = true
}

async function submitConfirm() {
  if (!requireOperator(confirmError)) return
  acting.value = true; confirmError.value = ''
  try {
    const wasAnomaly = detail.value.status === '异常待复核'
    detail.value = await api.confirmDosingRecord(detail.value.id, {
      operator: operator.value.trim(), role: props.role, note: confirmNote.value.trim()
    })
    tip.value = wasAnomaly ? `批次 #${detail.value.id} 复核通过，已确认` : `批次 #${detail.value.id} 已确认`
    confirmDialog.value = false
    await load()
  } catch (e) {
    confirmError.value = e.message
    await refreshDetail()
  } finally { acting.value = false }
}

const anomalyDialog = ref(false)
const anomalyReason = ref('')
const anomalyError = ref('')

function openMarkAnomaly() {
  anomalyReason.value = ''
  anomalyError.value = ''
  anomalyDialog.value = true
}

async function submitMarkAnomaly() {
  if (!requireOperator(anomalyError)) return
  if (!anomalyReason.value.trim()) { anomalyError.value = '标记异常必须说明原因'; return }
  acting.value = true; anomalyError.value = ''
  try {
    detail.value = await api.markDosingAnomaly(detail.value.id, {
      operator: operator.value.trim(), role: props.role, reason: anomalyReason.value.trim()
    })
    tip.value = `批次 #${detail.value.id} 已标记为异常待复核`
    anomalyDialog.value = false
    await load()
  } catch (e) {
    anomalyError.value = e.message
    await refreshDetail()
  } finally { acting.value = false }
}

const returnDialog = ref(false)
const returnReason = ref('')
const returnError = ref('')

function openReviewReturn() {
  returnReason.value = ''
  returnError.value = ''
  returnDialog.value = true
}

async function submitReviewReturn() {
  if (!requireOperator(returnError)) return
  if (!returnReason.value.trim()) { returnError.value = '复核退回必须记录原因'; return }
  acting.value = true; returnError.value = ''
  try {
    detail.value = await api.reviewReturnDosingRecord(detail.value.id, {
      operator: operator.value.trim(), role: props.role, reason: returnReason.value.trim()
    })
    tip.value = `批次 #${detail.value.id} 已复核退回，原始登记保留`
    returnDialog.value = false
    await load()
  } catch (e) {
    returnError.value = e.message
    await refreshDetail()
  } finally { acting.value = false }
}

// ==================== 工艺配置（管理员维护） ====================

const configDialog = ref(false)
const configError = ref('')
const configSaving = ref(false)
const newChem = ref('')
const newTarget = ref('')

const configChems = computed(() => configItems.value.filter(i => i.itemType === '药剂'))
const configTargets = computed(() => configItems.value.filter(i => i.itemType === '目标工段'))

async function openConfig() {
  configError.value = ''
  newChem.value = ''; newTarget.value = ''
  await loadConfig()
  configDialog.value = true
}

async function addConfig(itemType) {
  const name = itemType === '药剂' ? newChem.value.trim() : newTarget.value.trim()
  if (!name) { configError.value = `请填写${itemType}名称`; return }
  configSaving.value = true; configError.value = ''
  try {
    await api.addProcessConfig({ itemType, itemName: name, role: props.role })
    if (itemType === '药剂') newChem.value = ''; else newTarget.value = ''
    await loadConfig()
    await load()
  } catch (e) { configError.value = e.message } finally { configSaving.value = false }
}

async function toggleConfig(item) {
  configSaving.value = true; configError.value = ''
  try {
    await api.toggleProcessConfig(item.id, { enabled: !item.enabled, role: props.role })
    tip.value = item.enabled
      ? `「${item.itemName}」已停用，历史记录保留并标注「配置已变更」`
      : `「${item.itemName}」已启用`
    await loadConfig()
    await load()
  } catch (e) { configError.value = e.message } finally { configSaving.value = false }
}

// ==================== 异常复核板（仅管理员） ====================

const anomalyDrawer = ref(false)
const anomalyRows = ref([])
const anomalyLoading = ref(false)
const boardError = ref('')

async function openAnomalyBoard() {
  anomalyLoading.value = true; boardError.value = ''
  anomalyDrawer.value = true
  try {
    anomalyRows.value = await api.listDosingAnomalies({ role: props.role }) || []
  } catch (e) { boardError.value = e.message } finally { anomalyLoading.value = false }
}

async function openDetailFromBoard(row) {
  anomalyDrawer.value = false
  await openDetail(row)
}

onMounted(async () => {
  await Promise.all([load(), loadConfig()])
})
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">加药批次 · 记录与异常复核</span>
        <span class="panel-desc">待确认 → 已确认；异常批次复核退回保留原始登记；同一工段同一执行时间仅一个批次</span>
        <span class="spacer"></span>
        <span class="op-label">当前值班</span>
        <el-input v-model="operator" placeholder="值班人姓名" style="width: 130px" size="small"
          @change="persistOperator" />
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">待确认</div><div class="v">{{ stats.pending }}</div></div>
        <div class="stat"><div class="k">已确认</div><div class="v">{{ stats.confirmed }}</div></div>
        <div class="stat"><div class="k">异常待复核</div><div class="v" :style="stats.anomaly ? 'color:#c0392b' : ''">{{ stats.anomaly }}</div></div>
        <div class="stat"><div class="k">已退回</div><div class="v">{{ stats.returned }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="targetFilter" placeholder="全部目标工段" clearable style="width: 140px" @change="load">
          <el-option v-for="t in targetFilterOptions" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至"
          start-placeholder="执行时间起" end-placeholder="执行时间止"
          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 340px" @change="load" />
        <el-button @click="load">刷新</el-button>
        <span class="spacer"></span>
        <el-button v-if="isAdmin" type="danger" plain @click="openAnomalyBoard">异常复核</el-button>
        <el-button v-if="isAdmin" @click="openConfig">工艺配置</el-button>
        <el-button type="primary" :disabled="!canRegister" @click="openCreate">
          {{ canRegister ? '登记加药批次' : '仅运行工/管理员可登记' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column label="批次" width="70">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="药剂" min-width="150">
          <template #default="{ row }">
            <el-tag effect="plain">{{ row.chemName }}</el-tag>
            <el-tooltip v-if="row.chemOutOfConfig" content="登记后工艺配置已调整，该药剂已不在当前配置中；历史记录保留，不回写" placement="top">
              <el-tag type="warning" size="small" style="margin-left:6px">配置已变更</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="投加量" width="110">
          <template #default="{ row }">{{ row.doseMgPerL }} mg/L</template>
        </el-table-column>
        <el-table-column label="目标工段" min-width="140">
          <template #default="{ row }">
            {{ row.targetSection }}
            <el-tooltip v-if="row.targetOutOfConfig" content="登记后工艺配置已调整，该工段已不在当前配置中；历史记录保留，不回写" placement="top">
              <el-tag type="warning" size="small" style="margin-left:6px">配置已变更</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="执行时间" width="160">
          <template #default="{ row }">{{ fmt(row.executeAt) }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作者" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常 / 复核结论" min-width="180">
          <template #default="{ row }">
            <span v-if="row.status === '异常待复核'" style="color:#c0392b">{{ row.anomalyReason }}</span>
            <span v-else-if="row.status === '已退回'" style="color:#8a6d1a">{{ row.reviewReason }}</span>
            <span v-else-if="row.status === '已确认' && row.reviewReason">{{ row.reviewReason }}</span>
            <span v-else style="color:#9ab">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canEditRow(row)" size="small" type="primary" plain @click="openEdit(row)">修改</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ==================== 登记批次 ==================== -->
    <el-dialog v-model="createDialog" title="登记加药批次" width="560px" :close-on-click-modal="false">
      <div v-if="createError" class="err">{{ createError }}</div>
      <el-form :model="createForm" label-width="120px" class="dialog-form">
        <el-form-item label="药剂" required>
          <el-select v-model="createForm.chemName" placeholder="当前工艺配置中的药剂" style="width: 100%">
            <el-option v-for="c in chemOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="投加量(mg/L)" required>
          <el-input-number v-model="createForm.doseMgPerL" :min="0.01" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="目标工段" required>
          <el-select v-model="createForm.targetSection" placeholder="当前工艺配置中的工段" style="width: 100%">
            <el-option v-for="t in targetOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行时间" required>
          <el-date-picker v-model="createForm.executeAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="批次执行时间（同工段同时间仅一个批次）" />
        </el-form-item>
        <el-form-item label="操作者" required class="full">
          <el-input v-model="createForm.operator" placeholder="登记人姓名" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="createForm.note" placeholder="选填" /></el-form-item>
      </el-form>
      <div class="form-hint">五项必填：药剂、投加量、目标工段、执行时间、操作者；药剂与目标工段必须在当前工艺配置中</div>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 修改登记（本人 + 待确认） ==================== -->
    <el-dialog v-model="editDialog" title="修改登记（仅待确认）" width="560px" :close-on-click-modal="false">
      <div v-if="editError" class="err">{{ editError }}</div>
      <el-form :model="editForm" label-width="120px" class="dialog-form">
        <el-form-item label="药剂" required>
          <el-select v-model="editForm.chemName" style="width: 100%">
            <el-option v-for="c in chemOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="投加量(mg/L)" required>
          <el-input-number v-model="editForm.doseMgPerL" :min="0.01" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="目标工段" required>
          <el-select v-model="editForm.targetSection" style="width: 100%">
            <el-option v-for="t in targetOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行时间" required>
          <el-date-picker v-model="editForm.executeAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="操作者" class="full">
          <el-input v-model="editForm.operator" disabled />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="editForm.note" placeholder="选填" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" :disabled="editSaving" @click="submitEdit">保存修改</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 批次详情 ==================== -->
    <el-drawer v-model="drawer" size="680px" :title="detail ? `加药批次 #${detail.id} · ${detail.targetSection}` : '批次详情'">
      <div v-if="detail" style="padding: 0 4px 20px">
        <div v-if="detailError" class="err">{{ detailError }}</div>

        <div v-if="detail.chemOutOfConfig || detail.targetOutOfConfig" class="warn-tip">
          登记后工艺配置已调整（{{ [detail.chemOutOfConfig && '药剂', detail.targetOutOfConfig && '目标工段'].filter(Boolean).join('、') }} 已不在当前配置中）；
          历史记录按登记时的值保留，不回写。
        </div>

        <el-descriptions :column="2" border size="small" style="margin-bottom: 14px">
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作者">{{ detail.operatorName }}</el-descriptions-item>
          <el-descriptions-item label="药剂">
            {{ detail.chemName }}
            <el-tag v-if="detail.chemOutOfConfig" type="warning" size="small" style="margin-left:6px">配置已变更</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="投加量">{{ detail.doseMgPerL }} mg/L</el-descriptions-item>
          <el-descriptions-item label="目标工段">
            {{ detail.targetSection }}
            <el-tag v-if="detail.targetOutOfConfig" type="warning" size="small" style="margin-left:6px">配置已变更</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="执行时间">{{ fmt(detail.executeAt) }}</el-descriptions-item>
          <el-descriptions-item label="登记时间">{{ fmt(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.note || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.confirmedBy" label="确认人">{{ detail.confirmedBy }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.confirmedAt" label="确认时间">{{ fmt(detail.confirmedAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.anomalyReason" label="异常原因" :span="2">
            <span style="color:#c0392b">{{ detail.anomalyReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.reviewedBy" label="复核人">{{ detail.reviewedBy }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.reviewedAt" label="复核时间">{{ fmt(detail.reviewedAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.reviewReason" label="复核结论" :span="2">
            <span :style="detail.status === '已退回' ? 'color:#8a6d1a' : ''">{{ detail.reviewReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.status === '已退回'" class="warn-tip">
          该批次已复核退回，原始登记保留，不能覆盖成另一条正常记录；如需重新投加，请登记新批次。
        </div>

        <!-- 管理员复核操作：与后端校验口径一致 -->
        <template v-if="isAdmin">
          <div v-if="detail.status === '待确认'" class="action-bar">
            <span class="hint">核对现场回执后确认为有效批次；发现投加量 / 药剂 / 目标工段异常时标记异常</span>
            <div style="display:flex;gap:10px;margin-top:8px">
              <el-button type="success" :loading="acting" :disabled="acting" @click="openConfirm">核对回执并确认</el-button>
              <el-button type="danger" plain :disabled="acting" @click="openMarkAnomaly">标记异常</el-button>
            </div>
          </div>
          <div v-else-if="detail.status === '异常待复核'" class="action-bar">
            <span class="hint">复核异常批次：确认无误可通过；退回必须记录原因，原始登记保留</span>
            <div style="display:flex;gap:10px;margin-top:8px">
              <el-button type="success" :loading="acting" :disabled="acting" @click="openConfirm">复核通过</el-button>
              <el-button type="warning" :disabled="acting" @click="openReviewReturn">复核退回</el-button>
            </div>
          </div>
        </template>

        <!-- 操作留痕 -->
        <div class="sec-title">操作记录</div>
        <el-timeline style="padding-left: 4px">
          <el-timeline-item v-for="log in [...detail.logs].reverse()" :key="log.id"
            :timestamp="fmt(log.createdAt)" placement="top"
            :type="log.action === '标记异常' || log.action === '复核退回' ? 'danger' : log.action === '确认' || log.action === '复核通过' ? 'success' : 'primary'">
            <div style="font-weight:700">
              {{ log.action }}
              <span v-if="log.fromStatus !== log.toStatus" style="font-weight:400;color:#6b8a96">
                {{ log.fromStatus || '—' }} → {{ log.toStatus }}
              </span>
              <span style="font-weight:400;color:#6b8a96"> · {{ log.operatorName }}</span>
            </div>
            <div style="font-size:12px;color:#6b8a96">{{ log.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- ==================== 确认 / 复核通过 ==================== -->
    <el-dialog v-model="confirmDialog" :title="detail && detail.status === '异常待复核' ? '复核通过' : '核对回执并确认'" width="440px">
      <div v-if="confirmError" class="err">{{ confirmError }}</div>
      <p style="margin:0 0 12px;font-size:13px">
        批次 <b>#{{ detail?.id }}</b>（{{ detail?.chemName }} {{ detail?.doseMgPerL }} mg/L → {{ detail?.targetSection }}）
        将转为「已确认」。
      </p>
      <el-input v-model="confirmNote" type="textarea" :rows="3" placeholder="现场回执核对说明（选填）" />
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="success" :loading="acting" :disabled="acting" @click="submitConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 标记异常 ==================== -->
    <el-dialog v-model="anomalyDialog" title="标记异常" width="440px">
      <div v-if="anomalyError" class="err">{{ anomalyError }}</div>
      <p style="margin:0 0 12px;font-size:13px">
        批次 <b>#{{ detail?.id }}</b> 将转为「异常待复核」，请说明异常原因（投加量 / 药剂 / 目标工段）：
      </p>
      <el-input v-model="anomalyReason" type="textarea" :rows="3" placeholder="如：投加量超出常规区间，待现场复核" />
      <template #footer>
        <el-button @click="anomalyDialog = false">取消</el-button>
        <el-button type="danger" :loading="acting" :disabled="acting" @click="submitMarkAnomaly">标记异常</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 复核退回 ==================== -->
    <el-dialog v-model="returnDialog" title="复核退回" width="440px">
      <div v-if="returnError" class="err">{{ returnError }}</div>
      <p style="margin:0 0 12px;font-size:13px">
        批次 <b>#{{ detail?.id }}</b> 将转为「已退回」。原始登记保留，不能覆盖成另一条正常记录；请记录退回原因：
      </p>
      <el-input v-model="returnReason" type="textarea" :rows="3" placeholder="如：现场回执与登记不符，退回重新登记" />
      <template #footer>
        <el-button @click="returnDialog = false">取消</el-button>
        <el-button type="warning" :loading="acting" :disabled="acting" @click="submitReviewReturn">确认退回</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 工艺配置（管理员） ==================== -->
    <el-dialog v-model="configDialog" title="工艺配置 · 药剂与目标工段" width="640px">
      <div v-if="configError" class="err">{{ configError }}</div>
      <div class="form-hint" style="margin-bottom:12px">
        登记时药剂与目标工段必须在启用项内；停用后新登记不可选，历史记录保留并标注「配置已变更」，不会改写。
      </div>
      <div class="config-cols">
        <div>
          <div class="sec-title">药剂</div>
          <div v-for="item in configChems" :key="item.id" class="config-row">
            <span>{{ item.itemName }}</span>
            <el-tag :type="item.enabled ? 'success' : 'info'" size="small">{{ item.enabled ? '启用' : '停用' }}</el-tag>
            <el-button size="small" :type="item.enabled ? 'warning' : 'success'" plain
              :loading="configSaving" @click="toggleConfig(item)">{{ item.enabled ? '停用' : '启用' }}</el-button>
          </div>
          <div class="config-add">
            <el-input v-model="newChem" placeholder="新药剂名称" size="small" />
            <el-button size="small" type="primary" :loading="configSaving" @click="addConfig('药剂')">添加</el-button>
          </div>
        </div>
        <div>
          <div class="sec-title">目标工段</div>
          <div v-for="item in configTargets" :key="item.id" class="config-row">
            <span>{{ item.itemName }}</span>
            <el-tag :type="item.enabled ? 'success' : 'info'" size="small">{{ item.enabled ? '启用' : '停用' }}</el-tag>
            <el-button size="small" :type="item.enabled ? 'warning' : 'success'" plain
              :loading="configSaving" @click="toggleConfig(item)">{{ item.enabled ? '停用' : '启用' }}</el-button>
          </div>
          <div class="config-add">
            <el-input v-model="newTarget" placeholder="新目标工段" size="small" />
            <el-button size="small" type="primary" :loading="configSaving" @click="addConfig('目标工段')">添加</el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="configDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 异常复核板（仅管理员） ==================== -->
    <el-drawer v-model="anomalyDrawer" size="720px" title="异常复核 · 全部异常批次">
      <div v-if="boardError" class="err">{{ boardError }}</div>
      <el-table :data="anomalyRows" v-loading="anomalyLoading" border stripe>
        <el-table-column label="批次" width="70">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column prop="chemName" label="药剂" width="110" />
        <el-table-column label="投加量" width="100">
          <template #default="{ row }">{{ row.doseMgPerL }} mg/L</template>
        </el-table-column>
        <el-table-column prop="targetSection" label="目标工段" width="100" />
        <el-table-column label="执行时间" width="150">
          <template #default="{ row }">{{ fmt(row.executeAt) }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作者" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openDetailFromBoard(row)">复核</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!anomalyLoading && !anomalyRows.length" class="form-hint" style="margin-top:12px">当前没有异常批次</div>
    </el-drawer>
  </div>
</template>

<style scoped>
.op-label { font-size: 12px; color: var(--muted); }
.sec-title {
  font-size: 13px; font-weight: 800; color: var(--primary-deep);
  border-left: 3px solid var(--primary); padding-left: 8px;
  margin: 16px 0 10px;
}
.action-bar { background: var(--primary-wash); border-radius: 8px; padding: 12px; margin-bottom: 6px; }
.hint { font-size: 12px; color: var(--muted); }
.form-hint { font-size: 12px; color: var(--muted); margin-top: 4px; }
.warn-tip {
  background: #fdf6e3; border: 1px solid #f0dfa8; color: #8a6d1a;
  padding: 8px 12px; border-radius: 8px; margin-bottom: 12px; font-size: 13px;
}
.config-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.config-row {
  display: flex; align-items: center; gap: 10px; padding: 6px 0;
  border-bottom: 1px dashed var(--line);
}
.config-row span:first-child { flex: 1; font-weight: 600; }
.config-add { display: flex; gap: 8px; margin-top: 10px; }
</style>
