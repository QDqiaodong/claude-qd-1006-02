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
const statuses = ['运行', '反洗']
const types = ['砂滤', '炭滤']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())

function blank() {
  return { filterCode: '', type: '砂滤', flowM3h: '', status: '运行', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listFilters(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }

async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      filterCode: form.value.filterCode,
      type: form.value.type,
      flowM3h: Number(form.value.flowM3h),
      status: form.value.status,
      note: form.value.note
    }
    await api.createFilter(d)
    tip.value = `滤池 ${d.filterCode} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function backwash(row) {
  error.value = ''; tip.value = ''
  try {
    await api.backwashFilter(row.id)
    tip.value = `${row.filterCode} 已下达反洗指令`
    await load()
  } catch (e) { error.value = e.message }
}

async function resume(row) {
  error.value = ''; tip.value = ''
  try {
    await api.resumeFilter(row.id)
    tip.value = `${row.filterCode} 已恢复运行`
    await load()
  } catch (e) { error.value = e.message }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">滤池 · 过滤工段</span>
        <span class="panel-desc">类型限 砂滤 / 炭滤，流量 &gt; 0；反洗将状态置为「反洗」，结束后恢复运行</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">滤池数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ rows.filter(r => r.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">反洗</div><div class="v">{{ rows.filter(r => r.status === '反洗').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" :disabled="!canOperate" @click="openCreate">
          {{ canOperate ? '新建滤池' : '仅运行工/管理员可操作' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="filterCode" label="滤池编号" width="120" />
        <el-table-column prop="type" label="类型" width="90">
          <template #default="{ row }"><el-tag effect="plain">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="flowM3h" label="流量(m³/h)" width="120" />
        <el-table-column prop="note" label="备注" min-width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '运行' ? 'success' : 'warning'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :disabled="!canOperate || row.status === '反洗'" @click="backwash(row)">反洗</el-button>
            <el-button size="small" type="success" plain :disabled="!canOperate || row.status === '运行'" @click="resume(row)">恢复运行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建滤池" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="滤池编号"><el-input v-model="form.filterCode" placeholder="如 F-01" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="t in types" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="流量(m³/h)"><el-input v-model="form.flowM3h" type="number" step="0.1" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>
