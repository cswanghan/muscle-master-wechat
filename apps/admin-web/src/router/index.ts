import { createRouter, createWebHistory } from 'vue-router'
import Health from '../views/Health.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/health' },
    { path: '/health', name: 'health', component: Health },
  ],
})

export default router
