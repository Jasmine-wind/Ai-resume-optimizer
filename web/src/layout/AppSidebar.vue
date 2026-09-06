<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import ProductMark from '@/components/common/ProductMark.vue'

defineProps<{
  /** 窄屏 Drawer 展开状态。桌面端由 Global Topbar 承担导航。 */
  open?: boolean
  /** 仅窄屏 Drawer 关闭时从辅助技术与 tab 顺序中移除导航。 */
  drawer?: boolean
}>()

const emit = defineEmits<{
  close: []
  navigate: []
}>()

const authStore = useAuthStore()

const primaryNavItems = [
  {
    label: '开始优化',
    route: '/app',
  },
  {
    label: '我的简历',
    route: '/resumes',
  },
]

onMounted(() => {
  if (authStore.isAuthenticated && !authStore.currentUser) {
    authStore.fetchMe().catch(() => undefined)
  }
})
</script>

<template>
  <aside
    id="app-primary-navigation"
    class="app-sidebar"
    :class="{ 'is-open': open }"
    :aria-hidden="drawer && !open"
    :inert="drawer && !open"
  >
    <div class="app-sidebar-header">
      <RouterLink to="/app" class="app-sidebar-brand" @click="emit('navigate')">
        <ProductMark size="sm" />
        <div>
          <strong>简历优化</strong>
          <small>为目标岗位准备简历</small>
        </div>
      </RouterLink>
      <button
        v-if="drawer"
        type="button"
        class="app-sidebar-close"
        aria-label="关闭导航菜单"
        @click="emit('close')"
      >
        <span aria-hidden="true">×</span>
      </button>
    </div>

    <nav class="app-sidebar-nav" aria-label="主导航">
      <RouterLink
        v-for="item in primaryNavItems"
        :key="item.route"
        :to="item.route"
        class="app-sidebar-link"
        @click="emit('navigate')"
      >
        {{ item.label }}
      </RouterLink>
    </nav>

    <div class="app-sidebar-trust" aria-label="产品原则">
      <span aria-hidden="true" />
      <p>
        <strong>真实性优先</strong>
        <small>只依据已确认的简历材料</small>
      </p>
    </div>
  </aside>
</template>
