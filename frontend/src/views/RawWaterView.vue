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

async function reclassify(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.reclassifyRawWater(row.id, target)
    tip.value = `${row.sourceName} 已重判为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
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
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === '超标'" size="small" type="warning"
              :disabled="!(role === '运行工' || role === '管理员')" @click="goDisposal(row)">发起处置</el-button>
            <el-button size="small" type="danger" plain :disabled="!canReclassify" @click="reclassify(row, '超标')">判超标</el-button>
            <el-button size="small" :disabled="!canReclassify" @click="reclassify(row, '合格')">复位合格</el-button>
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
  </div>
</template>
