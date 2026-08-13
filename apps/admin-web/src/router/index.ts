import { createRouter, createWebHistory } from 'vue-router'
import Health from '../views/Health.vue'
import Catalog from '../views/Catalog.vue'
import Frontdesk from '../views/Frontdesk.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/health' },
    { path: '/health', name: 'health', component: Health },
    { path: '/catalog', name: 'catalog', component: Catalog },
    { path: '/frontdesk', name: 'frontdesk', component: Frontdesk },
  ],
})

export default router
