import { createRouter, createWebHistory } from 'vue-router'
import Health from '../views/Health.vue'
import Catalog from '../views/Catalog.vue'
import Orders from '../views/Orders.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/health' },
    { path: '/health', name: 'health', component: Health },
    { path: '/catalog', name: 'catalog', component: Catalog },
    { path: '/orders', name: 'orders', component: Orders },
  ],
})

export default router
