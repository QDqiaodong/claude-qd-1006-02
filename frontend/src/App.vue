<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import RawWaterView from './views/RawWaterView.vue'
import SedimentationTankView from './views/SedimentationTankView.vue'
import FilterView from './views/FilterView.vue'
import DosingView from './views/DosingView.vue'
import DisposalView from './views/DisposalView.vue'

// ------------------------------------------------------------------
// 导航形态：顶部「角色切换」（运行工 / 化验员 / 管理员），切换后主区展示
// 对应权限的操作；右侧竖排标签切换业务模块（取水→沉淀→过滤→消毒加药 工艺链）。
// main.js 没有 app.use(router)，这里直接驱动 router.currentRoute。
// ------------------------------------------------------------------

const viewMap = {
  '/raw-water': RawWaterView,
  '/sediment': SedimentationTankView,
  '/filter': FilterView,
  '/dosing': DosingView,
  '/disposal': DisposalView
}

const tabs = [
  { path: '/raw-water', label: '原水监测', sub: '取水口 · 水质建档' },
  { path: '/sediment', label: '沉淀池', sub: '沉淀 · 停留时间' },
  { path: '/filter', label: '滤池', sub: '砂滤 / 炭滤 · 反洗' },
  { path: '/dosing', label: '加药', sub: '批次登记 · 异常复核' },
  { path: '/disposal', label: '超标处置', sub: '待处置 → 执行中 → 已解除' }
]

const roles = ['运行工', '化验员', '管理员']
const currentRole = ref('运行工')
const currentPath = ref(router.currentRoute.value.path)
const counts = ref({ 'raw-water': 0, sediment: 0, filter: 0, dosing: 0, disposal: 0 })

const currentView = computed(() => viewMap[currentPath.value] || RawWaterView)

const roleDesc = computed(() => {
  if (currentRole.value === '运行工') return '运行工：可操作沉淀池 / 滤池 / 加药 / 超标处置，原水只读'
  if (currentRole.value === '化验员') return '化验员：负责原水取样建档与判级，其余只读'
  return '管理员：全部操作权限，含原水重判（超标）与超标处置'
})

async function loadCounts() {
  try {
    const [r, s, f, d, dp] = await Promise.all([
      api.listRawWaters(), api.listTanks(), api.listFilters(), api.listDosingRecords(), api.listDisposals()
    ])
    counts.value = {
      'raw-water': (r || []).length,
      sediment: (s || []).length,
      filter: (f || []).length,
      dosing: (d || []).length,
      disposal: (dp || []).filter(x => x.status !== '已解除').length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function setRole(r) {
  if (r === currentRole.value) return
  currentRole.value = r
}

function go(path) {
  router.push(path)
  currentPath.value = path
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">水</span>
        <div class="brand-text">
          <div class="brand-title">自来水厂 · 工艺链监控</div>
          <div class="brand-sub">取水 → 沉淀 → 过滤 → 消毒 → 清水池</div>
        </div>
      </div>

      <div class="topbar-right">
        <span class="role-label">当前角色</span>
        <div class="role-switch">
          <button
            v-for="r in roles"
            :key="r"
            :class="{ on: currentRole === r }"
            @click="setRole(r)"
          >{{ r }}</button>
        </div>
      </div>
    </header>

    <div class="role-tip">{{ roleDesc }}</div>

    <div class="body">
      <main class="main">
        <component :is="currentView" :role="currentRole" />
      </main>

      <aside class="right-rail">
        <div class="rail-title">工艺模块</div>
        <div
          v-for="t in tabs"
          :key="t.path"
          class="rail-tab"
          :class="{ on: currentPath === t.path }"
          @click="go(t.path)"
        >
          <span class="t-count">{{ counts[t.path.slice(1)] }}</span>
          <div class="t-label">{{ t.label }}</div>
          <div class="t-sub">{{ t.sub }}</div>
        </div>
      </aside>
    </div>
  </div>
</template>
