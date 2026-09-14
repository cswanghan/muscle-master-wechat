import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Schedule from '../views/Schedule.vue'
import Health from '../views/Health.vue'
import Catalog from '../views/Catalog.vue'
import Orders from '../views/Orders.vue'
import Frontdesk from '../views/Frontdesk.vue'
import MiniPreview from '../views/MiniPreview.vue'
import Levels from '../views/Levels.vue'
import Employment from '../views/Employment.vue'
import Finance from '../views/Finance.vue'
import Members from '../views/Members.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: Dashboard },
    { path: '/schedule', name: 'schedule', component: Schedule },
    { path: '/health', name: 'health', component: Health },
    { path: '/catalog', name: 'catalog', component: Catalog },
    { path: '/orders', name: 'orders', component: Orders },
    { path: '/frontdesk', name: 'frontdesk', component: Frontdesk },
    { path: '/levels', name: 'levels', component: Levels },
    { path: '/employment', name: 'employment', component: Employment },
    { path: '/members', name: 'members', component: Members },
    { path: '/finance', name: 'finance', component: Finance },
    { path: '/mini', name: 'mini', component: MiniPreview },
  ],
})

export default router
