<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const props = defineProps({ role: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')

const canOperate = computed(() => props.role === '运行工' || props.role === '管理员')
const statuses = ['运行', '清洗']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())

function blank() {
  return { tankCode: '', capacityM3: '', retentionMin: '', status: '运行', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listTanks(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }

async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      tankCode: form.value.tankCode,
      capacityM3: Number(form.value.capacityM3),
      retentionMin: Number(form.value.retentionMin),
      status: form.value.status,
      note: form.value.note
    }
    await api.createTank(d)
    tip.value = `沉淀池 ${d.tankCode} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferTank(row.id, target)
    tip.value = `${row.tankCode} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">沉淀池 · 沉淀工段</span>
        <span class="panel-desc">停留时间必须大于 0 分钟才能建档；状态仅 运行 ↔ 清洗 流转</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">池数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ rows.filter(r => r.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">清洗</div><div class="v">{{ rows.filter(r => r.status === '清洗').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" :disabled="!canOperate" @click="openCreate">
          {{ canOperate ? '新建沉淀池' : '仅运行工/管理员可操作' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="tankCode" label="池编号" width="120" />
        <el-table-column prop="capacityM3" label="容积(m³)" width="120" />
        <el-table-column prop="retentionMin" label="停留(min)" width="110" />
        <el-table-column prop="note" label="备注" min-width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '运行' ? 'success' : 'warning'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :disabled="!canOperate || row.status === '清洗'" @click="transfer(row, '清洗')">清洗</el-button>
            <el-button size="small" type="success" plain :disabled="!canOperate || row.status === '运行'" @click="transfer(row, '运行')">恢复运行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建沉淀池" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="池编号"><el-input v-model="form.tankCode" placeholder="如 SED-01" /></el-form-item>
        <el-form-item label="容积(m³)"><el-input v-model="form.capacityM3" type="number" step="0.01" /></el-form-item>
        <el-form-item label="停留(min)" class="full"><el-input v-model="form.retentionMin" type="number" step="1" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>
