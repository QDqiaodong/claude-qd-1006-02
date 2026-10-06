<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import router from '../router'

const props = defineProps({ role: String })

// ------------------------------------------------------------------
// 超标处置闭环：待处置 → 执行中 → 已解除。
// 所有状态以后端为准：每次操作后端返回最新详情，刷新 / 重进都从这里重新拉取。
// ------------------------------------------------------------------

const canOperate = computed(() => props.role === '运行工' || props.role === '管理员')

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')

// 当前值班人：所有操作的操作人，持久化到 localStorage，刷新不丢
const operator = ref(localStorage.getItem('disposal-operator') || '')

const stats = computed(() => ({
  pending: rows.value.filter(r => r.status === '待处置').length,
  running: rows.value.filter(r => r.status === '执行中').length,
  resolved: rows.value.filter(r => r.status === '已解除').length,
  conflicts: rows.value.reduce((n, r) => n + (r.conflictCount || 0), 0)
}))

const activeSources = computed(() =>
  new Set(rows.value.filter(r => r.status !== '已解除').map(r => r.sourceName))
)

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

function statusTag(s) {
  return s === '已解除' ? 'success' : s === '执行中' ? 'primary' : 'warning'
}

function linkTag(s) {
  return s === '正常' ? 'success' : s === '冲突' ? 'danger' : 'info'
}

async function load() {
  loading.value = true; error.value = ''
  try {
    rows.value = await api.listDisposals(statusFilter.value ? { status: statusFilter.value } : {}) || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function persistOperator() {
  localStorage.setItem('disposal-operator', operator.value || '')
}

// ==================== 发起处置 ====================

const createDialog = ref(false)
const createError = ref('')
const saving = ref(false)
const exceedRecords = ref([])
const runningTanks = ref([])
const runningFilters = ref([])
const createForm = ref({ rawWaterId: null, owner: '', tankIds: [], filterIds: [] })

async function openCreate(preselectRawWaterId) {
  createError.value = ''
  createForm.value = { rawWaterId: preselectRawWaterId || null, owner: operator.value, tankIds: [], filterIds: [] }
  try {
    const [exceeds, tanks, filters] = await Promise.all([
      api.listRawWaters({ status: '超标' }),
      api.listTanks({ status: '运行' }),
      api.listFilters({ status: '运行' })
    ])
    exceedRecords.value = exceeds || []
    runningTanks.value = tanks || []
    runningFilters.value = filters || []
    const pre = exceedRecords.value.find(r => r.id === createForm.value.rawWaterId)
    if (pre && activeSources.value.has(pre.sourceName)) {
      // 该水源已有未解除处置：直接给出「已被接管」的明确提示
      const active = rows.value.find(d => d.sourceName === pre.sourceName && d.status !== '已解除')
      createError.value = `该水源的超标处置已被「${active?.owner}」接管（处置单 #${active?.id}，状态：${active?.status}），请勿重复发起`
      createForm.value.rawWaterId = null
    } else if (!pre) {
      createForm.value.rawWaterId = null
    }
    createDialog.value = true
  } catch (e) { error.value = e.message }
}

const selectedRecord = computed(() =>
  exceedRecords.value.find(r => r.id === createForm.value.rawWaterId))

async function submitCreate() {
  const f = createForm.value
  if (!f.rawWaterId) { createError.value = '请选择判为「超标」的原水记录'; return }
  if (!f.owner || !f.owner.trim()) { createError.value = '请填写责任人（值班人员）'; return }
  if (!f.tankIds.length || !f.filterIds.length) {
    createError.value = '需至少关联一台运行中的沉淀池和一台运行中的滤池'; return
  }
  saving.value = true; createError.value = ''
  try {
    const detail = await api.initiateDisposal({
      rawWaterId: f.rawWaterId, owner: f.owner.trim(), tankIds: f.tankIds, filterIds: f.filterIds
    })
    operator.value = f.owner.trim(); persistOperator()
    tip.value = `处置单 #${detail.id} 已发起（${detail.sourceName}）`
    createDialog.value = false
    await load()
    openDetail(detail.id)
  } catch (e) {
    // 并发接管 / 校验失败：后端返回的明确原因直接展示（如「已被张三接管」）
    createError.value = e.message
    await load()
  } finally { saving.value = false }
}

// ==================== 详情 ====================

const drawer = ref(false)
const detail = ref(null)
const detailError = ref('')

const conflicts = computed(() =>
  (detail.value?.equipments || []).filter(e => e.linkStatus === '冲突'))

const stepActive = computed(() => {
  const s = detail.value?.status
  return s === '已解除' ? 3 : s === '执行中' ? 2 : 1
})

async function openDetail(id) {
  detailError.value = ''
  try {
    detail.value = await api.getDisposal(id)
    drawer.value = true
  } catch (e) { error.value = e.message }
}

async function refreshDetail(id) {
  detail.value = await api.getDisposal(id)
  await load()
}

// ==================== 状态推进 ====================

const acting = ref(false)

async function doStart() {
  if (!requireOperator()) return
  acting.value = true; detailError.value = ''
  try {
    detail.value = await api.startDisposal(detail.value.id, operator.value.trim())
    tip.value = `处置单 #${detail.value.id} 已进入执行中`
    await load()
  } catch (e) { detailError.value = e.message } finally { acting.value = false }
}

const conclusion = ref('')
const resolving = ref(false)

async function doResolve() {
  if (!requireOperator()) return
  if (!conclusion.value || !conclusion.value.trim()) {
    detailError.value = '解除时必须填写处理结论'; return
  }
  resolving.value = true; detailError.value = ''
  try {
    detail.value = await api.resolveDisposal(detail.value.id, {
      conclusion: conclusion.value.trim(), operator: operator.value.trim()
    })
    tip.value = `处置单 #${detail.value.id} 已解除`
    conclusion.value = ''
    await load()
  } catch (e) {
    // 解除失败：后端整体回滚，状态保持原样，重新拉一次确保页面一致
    detailError.value = e.message
    await refreshDetail(detail.value.id)
  } finally { resolving.value = false }
}

// ==================== 设备冲突：改选 / 排除 ====================

const replaceDialog = ref(false)
const replaceError = ref('')
const replaceSaving = ref(false)
const replaceTarget = ref(null) // 当前处理的冲突关联
const replaceEquipId = ref(null)

const replaceOptions = computed(() => {
  if (!replaceTarget.value || !detail.value) return []
  const linked = new Set((detail.value.equipments || [])
    .filter(e => e.linkStatus === '正常')
    .map(e => `${e.equipType}:${e.equipId}`))
  const source = replaceTarget.value.equipType === '沉淀池' ? runningTanks.value : runningFilters.value
  return source.filter(e => !linked.has(`${replaceTarget.value.equipType}:${e.id}`))
})

async function openReplace(link) {
  replaceTarget.value = link
  replaceEquipId.value = null
  replaceError.value = ''
  try {
    // 改选候选实时拉取，只列「运行」中的设备
    if (link.equipType === '沉淀池') {
      runningTanks.value = await api.listTanks({ status: '运行' }) || []
    } else {
      runningFilters.value = await api.listFilters({ status: '运行' }) || []
    }
    replaceDialog.value = true
  } catch (e) { detailError.value = e.message }
}

async function submitReplace() {
  if (!requireOperator()) return
  if (!replaceEquipId.value) { replaceError.value = '请选择改选后的设备'; return }
  replaceSaving.value = true; replaceError.value = ''
  try {
    detail.value = await api.replaceDisposalEquip(detail.value.id, replaceTarget.value.linkId, {
      newEquipId: replaceEquipId.value, operator: operator.value.trim()
    })
    tip.value = '已改选设备'
    replaceDialog.value = false
    await load()
  } catch (e) { replaceError.value = e.message } finally { replaceSaving.value = false }
}

const excludeDialog = ref(false)
const excludeError = ref('')
const excludeSaving = ref(false)
const excludeTarget = ref(null)
const excludeReason = ref('')

function openExclude(link) {
  excludeTarget.value = link
  excludeReason.value = ''
  excludeError.value = ''
  excludeDialog.value = true
}

async function submitExclude() {
  if (!requireOperator()) return
  if (!excludeReason.value || !excludeReason.value.trim()) {
    excludeError.value = '排除设备必须填写排除原因'; return
  }
  excludeSaving.value = true; excludeError.value = ''
  try {
    detail.value = await api.excludeDisposalEquip(detail.value.id, excludeTarget.value.linkId, {
      reason: excludeReason.value.trim(), operator: operator.value.trim()
    })
    tip.value = '已排除设备'
    excludeDialog.value = false
    await load()
  } catch (e) { excludeError.value = e.message } finally { excludeSaving.value = false }
}

// ==================== 加药登记 ====================

const chemNames = ['聚合氯化铝', '次氯酸钠']
const dosingForm = ref({ chemName: '聚合氯化铝', doseMgPerL: null, targetTank: '' })
const dosingSaving = ref(false)
const dosingError = ref('')

const dosingTargets = computed(() => {
  const codes = (detail.value?.equipments || [])
    .filter(e => e.linkStatus === '正常')
    .map(e => e.equipCode)
  return [...new Set([...codes, '清水池'])]
})

async function submitDosing() {
  if (!requireOperator()) return
  const f = dosingForm.value
  if (!f.doseMgPerL || f.doseMgPerL <= 0) { dosingError.value = '投加量必须大于 0'; return }
  if (!f.targetTank) { dosingError.value = '请选择或填写目标工段'; return }
  dosingSaving.value = true; dosingError.value = ''
  try {
    // 幂等：同一药剂 + 同一目标工段重复提交，后端去重返回同一条
    await api.registerDisposalDosing(detail.value.id, {
      chemName: f.chemName, doseMgPerL: f.doseMgPerL, targetTank: f.targetTank, operator: operator.value.trim()
    })
    tip.value = '加药已登记'
    dosingForm.value = { chemName: f.chemName, doseMgPerL: null, targetTank: '' }
    await refreshDetail(detail.value.id)
  } catch (e) { dosingError.value = e.message } finally { dosingSaving.value = false }
}

function requireOperator() {
  if (!operator.value || !operator.value.trim()) {
    detailError.value = '请先在右上角填写当前值班人'
    return false
  }
  persistOperator()
  return true
}

// ==================== 入口 ====================

onMounted(async () => {
  await load()
  // 从原水监测「发起处置」跳转进来：自动打开发起对话框并预选该记录
  const rawWaterId = Number(router.currentRoute.value.query.rawWaterId)
  if (rawWaterId && canOperate.value) {
    await openCreate(rawWaterId)
  }
})
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">原水超标 · 联动处置闭环</span>
        <span class="panel-desc">待处置 → 执行中 → 已解除；每个水源同一时刻仅允许一条未解除处置</span>
        <span class="spacer"></span>
        <span class="op-label">当前值班</span>
        <el-input v-model="operator" placeholder="值班人姓名" style="width: 140px" size="small"
          @change="persistOperator" />
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">待处置</div><div class="v">{{ stats.pending }}</div></div>
        <div class="stat"><div class="k">执行中</div><div class="v">{{ stats.running }}</div></div>
        <div class="stat"><div class="k">已解除</div><div class="v">{{ stats.resolved }}</div></div>
        <div class="stat"><div class="k">设备冲突</div><div class="v" :style="stats.conflicts ? 'color:#c0392b' : ''">{{ stats.conflicts }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in ['待处置', '执行中', '已解除']" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <span class="spacer"></span>
        <el-button type="primary" :disabled="!canOperate" @click="openCreate()">
          {{ canOperate ? '发起处置' : '仅运行工/管理员可发起' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column label="单号" width="80">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column prop="sourceName" label="水源" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" label="责任人" width="100" />
        <el-table-column label="关联工段" min-width="150">
          <template #default="{ row }">沉淀池 {{ row.tankCount }} 台 · 滤池 {{ row.filterCount }} 台</template>
        </el-table-column>
        <el-table-column label="加药" width="70">
          <template #default="{ row }">{{ row.dosingCount }} 条</template>
        </el-table-column>
        <el-table-column label="冲突" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.conflictCount" type="danger" size="small">{{ row.conflictCount }} 条</el-tag>
            <span v-else style="color:#9ab">无</span>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="解除时间" width="160">
          <template #default="{ row }">{{ fmt(row.resolvedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row.id)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ==================== 发起处置 ==================== -->
    <el-dialog v-model="createDialog" title="发起超标联动处置" width="640px" :close-on-click-modal="false">
      <div v-if="createError" class="err">{{ createError }}</div>
      <el-form label-width="110px" class="dialog-form">
        <el-form-item label="超标记录" class="full">
          <el-select v-model="createForm.rawWaterId" placeholder="选择判为「超标」的原水监测记录" style="width: 100%">
            <el-option v-for="r in exceedRecords" :key="r.id" :value="r.id"
              :disabled="activeSources.has(r.sourceName)"
              :label="`#${r.id} ${r.sourceName} · ${fmt(r.sampleAt)}${activeSources.has(r.sourceName) ? '（已有未解除处置）' : ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="selectedRecord" label="水样" class="full">
          <span style="font-size: 13px; color: #6b8a96">
            pH {{ selectedRecord.waterSample?.ph }} · 浊度 {{ selectedRecord.waterSample?.turbidityNtu }} NTU · 余氯 {{ selectedRecord.waterSample?.chlorineMgL }} mg/L
          </span>
        </el-form-item>
        <el-form-item label="责任人" class="full">
          <el-input v-model="createForm.owner" placeholder="值班人员姓名" />
        </el-form-item>
        <el-form-item label="沉淀池" class="full">
          <el-checkbox-group v-model="createForm.tankIds">
            <el-checkbox v-for="t in runningTanks" :key="t.id" :value="t.id">
              {{ t.tankCode }}（{{ t.note || '运行中' }}）
            </el-checkbox>
          </el-checkbox-group>
          <div v-if="!runningTanks.length" style="font-size:12px;color:#c0392b">当前没有「运行」中的沉淀池可选</div>
        </el-form-item>
        <el-form-item label="滤池" class="full">
          <el-checkbox-group v-model="createForm.filterIds">
            <el-checkbox v-for="f in runningFilters" :key="f.id" :value="f.id">
              {{ f.filterCode }}（{{ f.type }} · {{ f.note || '运行中' }}）
            </el-checkbox>
          </el-checkbox-group>
          <div v-if="!runningFilters.length" style="font-size:12px;color:#c0392b">当前没有「运行」中的滤池可选</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="submitCreate">发起处置</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 处置详情 ==================== -->
    <el-drawer v-model="drawer" size="760px" :title="detail ? `处置单 #${detail.id} · ${detail.sourceName}` : '处置详情'">
      <div v-if="detail" style="padding: 0 4px 20px">
        <div v-if="detailError" class="err">{{ detailError }}</div>

        <el-steps :active="stepActive" align-center finish-status="success" style="margin-bottom: 18px">
          <el-step title="待处置" :description="fmt(detail.createdAt)" />
          <el-step title="执行中" />
          <el-step title="已解除" :description="detail.resolvedAt ? fmt(detail.resolvedAt) : ''" />
        </el-steps>

        <el-descriptions :column="2" border size="small" style="margin-bottom: 14px">
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="责任人">{{ detail.owner }}</el-descriptions-item>
          <el-descriptions-item label="依据记录">原水监测 #{{ detail.rawWaterId }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ fmt(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.resolvedAt" label="解除时间">{{ fmt(detail.resolvedAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.conclusion" label="处理结论" :span="2">{{ detail.conclusion }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="conflicts.length" class="err" style="font-weight:600">
          存在 {{ conflicts.length }} 条设备冲突（{{ conflicts.map(c => c.equipCode).join('、') }}），
          请改选可用设备或填写排除原因，否则无法进入执行 / 解除。
        </div>

        <!-- 设备关联 -->
        <div class="sec-title">关联工段设备</div>
        <el-table :data="detail.equipments" border size="small" style="margin-bottom: 16px">
          <el-table-column prop="equipType" label="工段" width="70" />
          <el-table-column prop="equipCode" label="设备编号" width="90" />
          <el-table-column label="关联状态" width="90">
            <template #default="{ row }">
              <el-tag :type="linkTag(row.linkStatus)" size="small">{{ row.linkStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="设备当前状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.currentEquipStatus === '运行' ? 'success' : 'warning'" size="small" effect="plain">
                {{ row.currentEquipStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="冲突 / 排除原因" min-width="170">
            <template #default="{ row }">
              <span v-if="row.linkStatus === '冲突'" style="color:#c0392b">{{ row.conflictReason }}</span>
              <span v-else-if="row.excludeReason">{{ row.excludeReason }}</span>
              <span v-else style="color:#9ab">—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="detail.status !== '已解除'" label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <template v-if="row.linkStatus === '冲突'">
                <el-button size="small" type="primary" plain :disabled="!canOperate" @click="openReplace(row)">改选</el-button>
                <el-button size="small" type="warning" plain :disabled="!canOperate" @click="openExclude(row)">排除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <!-- 加药登记 -->
        <div class="sec-title">加药登记</div>
        <el-table :data="detail.dosings" border size="small" style="margin-bottom: 10px">
          <el-table-column prop="chemName" label="药剂" width="110" />
          <el-table-column label="投加量" width="110">
            <template #default="{ row }">{{ row.doseMgPerL }} mg/L</template>
          </el-table-column>
          <el-table-column prop="targetTank" label="目标工段" width="100" />
          <el-table-column prop="operatorName" label="登记人" width="90" />
          <el-table-column label="登记时间" min-width="150">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <div v-if="detail.status !== '已解除'" class="dosing-form">
          <el-select v-model="dosingForm.chemName" style="width: 140px">
            <el-option v-for="c in chemNames" :key="c" :label="c" :value="c" />
          </el-select>
          <el-input-number v-model="dosingForm.doseMgPerL" :min="0.01" :precision="2" :step="1"
            placeholder="投加量 mg/L" style="width: 150px" />
          <el-select v-model="dosingForm.targetTank" filterable allow-create default-first-option
            placeholder="目标工段" style="width: 140px">
            <el-option v-for="t in dosingTargets" :key="t" :label="t" :value="t" />
          </el-select>
          <el-button type="primary" plain :loading="dosingSaving" :disabled="!canOperate || dosingSaving"
            @click="submitDosing">登记加药</el-button>
        </div>
        <div v-if="dosingError" class="err" style="margin-top:8px">{{ dosingError }}</div>

        <!-- 状态推进 -->
        <template v-if="detail.status !== '已解除'">
          <div class="sec-title">状态推进</div>
          <div v-if="detail.status === '待处置'" class="action-bar">
            <span class="hint">确认设备与加药后进入执行中（需至少一台正常关联的沉淀池和一台滤池）</span>
            <el-button type="primary" :loading="acting" :disabled="!canOperate || acting || conflicts.length > 0"
              @click="doStart">确认设备并进入执行中</el-button>
          </div>
          <div v-else class="action-bar">
            <el-input v-model="conclusion" type="textarea" :rows="2" placeholder="处理结论（必填）：处置措施与复检结果"
              style="margin-bottom: 8px" />
            <div style="display:flex;align-items:center;gap:10px">
              <span class="hint">解除将记录结论与时间；存在未处理冲突时无法解除</span>
              <span class="spacer"></span>
              <el-button type="success" :loading="resolving" :disabled="!canOperate || resolving || conflicts.length > 0"
                @click="doResolve">解除处置</el-button>
            </div>
          </div>
        </template>

        <!-- 流转记录 -->
        <div class="sec-title">流转记录</div>
        <el-timeline style="padding-left: 4px">
          <el-timeline-item v-for="log in [...detail.logs].reverse()" :key="log.id"
            :timestamp="fmt(log.createdAt)" placement="top"
            :type="log.action === '设备冲突' ? 'danger' : log.action === '解除' ? 'success' : 'primary'">
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

    <!-- ==================== 改选设备 ==================== -->
    <el-dialog v-model="replaceDialog" title="改选设备" width="440px">
      <div v-if="replaceError" class="err">{{ replaceError }}</div>
      <p style="margin:0 0 12px;font-size:13px">
        冲突设备 <b>{{ replaceTarget?.equipCode }}</b>（{{ replaceTarget?.conflictReason }}），
        请选择一台「运行」中的{{ replaceTarget?.equipType }}替换：
      </p>
      <el-select v-model="replaceEquipId" placeholder="选择设备" style="width: 100%">
        <el-option v-for="e in replaceOptions" :key="e.id" :value="e.id"
          :label="replaceTarget?.equipType === '沉淀池' ? `${e.tankCode}（${e.note || '运行中'}）` : `${e.filterCode}（${e.type} · ${e.note || '运行中'}）`" />
      </el-select>
      <div v-if="!replaceOptions.length" style="font-size:12px;color:#c0392b;margin-top:8px">
        当前没有可改选的「运行」中设备，可考虑填写排除原因
      </div>
      <template #footer>
        <el-button @click="replaceDialog = false">取消</el-button>
        <el-button type="primary" :loading="replaceSaving" :disabled="replaceSaving" @click="submitReplace">确认改选</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 排除设备 ==================== -->
    <el-dialog v-model="excludeDialog" title="排除设备" width="440px">
      <div v-if="excludeError" class="err">{{ excludeError }}</div>
      <p style="margin:0 0 12px;font-size:13px">
        将冲突设备 <b>{{ excludeTarget?.equipCode }}</b> 从本次处置中排除，请填写排除原因：
      </p>
      <el-input v-model="excludeReason" type="textarea" :rows="3" placeholder="如：反洗预计 4 小时，处置期间改由其他滤池承担" />
      <template #footer>
        <el-button @click="excludeDialog = false">取消</el-button>
        <el-button type="warning" :loading="excludeSaving" :disabled="excludeSaving" @click="submitExclude">确认排除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.op-label { font-size: 12px; color: var(--muted); }
.sec-title {
  font-size: 13px; font-weight: 800; color: var(--primary-deep);
  border-left: 3px solid var(--primary); padding-left: 8px;
  margin: 16px 0 10px;
}
.dosing-form { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.action-bar { background: var(--primary-wash); border-radius: 8px; padding: 12px; }
.hint { font-size: 12px; color: var(--muted); }
</style>
