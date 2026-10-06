import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import RawWaterView from '../views/RawWaterView.vue'
import SedimentationTankView from '../views/SedimentationTankView.vue'
import FilterView from '../views/FilterView.vue'
import DosingView from '../views/DosingView.vue'
import DisposalView from '../views/DisposalView.vue'

const routes = [
  { path: '/', redirect: '/raw-water' },
  { path: '/raw-water', name: 'raw-water', component: RawWaterView },
  { path: '/sediment', name: 'sediment', component: SedimentationTankView },
  { path: '/filter', name: 'filter', component: FilterView },
  { path: '/dosing', name: 'dosing', component: DosingView },
  { path: '/disposal', name: 'disposal', component: DisposalView },
  { path: '/:pathMatch(.*)*', redirect: '/raw-water' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
