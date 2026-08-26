import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated } from '@/api/authApi.js'

// Route-level loading keeps the initial login bundle small; charting, Excel
// export and trading UI code are fetched only after the user needs that page.
const DisplayPage = () => import('@/views/DisplayPage.vue')
const ComparisonPage = () => import('@/views/ComparisonPage.vue')
const LoginPage = () => import('@/views/LoginPage.vue')
const DealPage = () => import('@/views/DealPage.vue')

const routes = [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    name: 'Login',
    component: LoginPage
  },
  {
    path: '/display',
    name: 'Display',
    component: DisplayPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/comparison',
    name: 'Comparison',
    component: ComparisonPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/deal',
    name: 'deal',
    component: DealPage,
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !isAuthenticated()) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'Login' && isAuthenticated()) return { name: 'Display' }
  return true
})

export default router
