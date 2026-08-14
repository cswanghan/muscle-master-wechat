import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Schedule from '../views/Schedule.vue'
import Health from '../views/Health.vue'
import Catalog from '../views/Catalog.vue'
import Orders from '../views/Orders.vue'
import Frontdesk from '../views/Frontdesk.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: Dashboard },
    { path: '/schedule', name: 'schedule', component: Schedule },
    { path: '/health', name: 'health', component: Health },
    { path: '/catalog', name: 'catalog', component: Catalog },
    { path: '/orders', name: 'orders', component: Orders },
    { path: '/frontdesk', name: 'frontdesk', component: Frontdesk },
  ],
})

export default router
