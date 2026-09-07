import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    guestOnly?: boolean
    title?: string
    layoutWidth?: 'standard' | 'reading' | 'focused'
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'landing',
      component: () => import('@/views/landing/LandingView.vue'),
      meta: {
        title: '岗位定向简历优化',
      },
    },
    {
      path: '/app',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: {
        requiresAuth: true,
        title: '首页',
        layoutWidth: 'standard',
      },
    },
    {
      path: '/job-direction-insights',
      name: 'job-direction-insights',
      component: () => import('@/views/insight/JobDirectionInsightView.vue'),
      meta: {
        requiresAuth: true,
        title: '岗位方向洞察',
        layoutWidth: 'standard',
      },
    },
    {
      path: '/job-analysis/:optimizationTaskId',
      name: 'job-analysis',
      component: () => import('@/views/job/JobAnalysisView.vue'),
      meta: {
        requiresAuth: true,
        title: '岗位分析',
        layoutWidth: 'focused',
      },
    },
    {
      path: '/workspace/:optimizationTaskId',
      name: 'workspace',
      component: () => import('@/views/workspace/WorkspaceView.vue'),
      meta: {
        requiresAuth: true,
        title: '优化工作区',
        layoutWidth: 'focused',
      },
    },
    {
      path: '/resumes',
      name: 'resumes',
      component: () => import('@/views/resume/ResumeView.vue'),
      meta: {
        requiresAuth: true,
        title: '我的简历',
        layoutWidth: 'standard',
      },
    },
    {
      path: '/settings/profile',
      name: 'profile-settings',
      component: () => import('@/views/settings/ProfileSettingsView.vue'),
      meta: {
        requiresAuth: true,
        title: '个人资料',
        layoutWidth: 'standard',
      },
    },
    {
      path: '/settings/ai-provider',
      name: 'ai-provider-settings',
      component: () => import('@/views/settings/AiProviderSettingsView.vue'),
      meta: {
        requiresAuth: true,
        title: 'AI 设置',
        layoutWidth: 'standard',
      },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: {
        guestOnly: true,
        title: '登录',
      },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/auth/RegisterView.vue'),
      meta: {
        guestOnly: true,
        title: '注册',
      },
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/app',
    },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return {
      name: 'login',
      query: {
        redirect: to.fullPath,
      },
    }
  }

  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return { name: 'home' }
  }

  return true
})

router.afterEach((to) => {
  if (typeof document === 'undefined') return
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · CV Role` : 'CV Role'
})

export default router
