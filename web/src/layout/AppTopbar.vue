<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import ProductMark from '@/components/common/ProductMark.vue'

defineProps<{
  menuOpen?: boolean
}>()

const emit = defineEmits<{
  toggleMenu: []
}>()

const router = useRouter()
const authStore = useAuthStore()

const displayName = computed(() => {
  const user = authStore.currentUser
  return user?.nickname || user?.username || '已登录用户'
})

const userEmail = computed(() => authStore.currentUser?.email || '当前账号')

const avatarInitials = computed(() => {
  const name = displayName.value.trim()
  if (!name) return 'CV'

  const glyphs = Array.from(name.replace(/\s+/gu, ''))
  const firstGlyph = glyphs[0]
  const firstCodePoint = firstGlyph?.codePointAt(0) ?? 0
  if (firstGlyph && firstCodePoint > 0x7f) return firstGlyph

  const words = name.split(/[\s._-]+/u).filter(Boolean)
  if (words.length > 1) {
    return words
      .slice(0, 2)
      .map((word) => Array.from(word)[0] ?? '')
      .join('')
      .toUpperCase()
  }
  return glyphs.slice(0, 2).join('').toUpperCase() || 'CV'
})

const primaryNavItems = [
  { label: '开始优化', route: '/app' },
  { label: '我的简历', route: '/resumes' },
]

const handleAccountCommand = (command: string) => {
  if (command === 'profile') {
    router.push('/settings/profile')
    return
  }
  if (command === 'ai-provider') {
    router.push('/settings/ai-provider')
    return
  }
  if (command === 'logout') {
    authStore.logout()
    router.push('/login')
  }
}
</script>

<template>
  <header class="app-topbar">
    <div class="app-topbar-left">
      <button
        type="button"
        class="app-topbar-menu-button"
        aria-label="打开导航菜单"
        aria-controls="app-primary-navigation"
        :aria-expanded="menuOpen"
        @click="emit('toggleMenu')"
      >
        <span class="app-menu-icon" aria-hidden="true"><i /><i /><i /></span>
        <span class="app-menu-label">菜单</span>
      </button>
      <RouterLink to="/app" class="app-topbar-brand" aria-label="简历优化首页">
        <ProductMark size="sm" />
        <span>简历优化</span>
      </RouterLink>
      <nav class="app-topbar-nav" aria-label="主导航">
        <RouterLink
          v-for="item in primaryNavItems"
          :key="item.route"
          :to="item.route"
          class="app-topbar-nav-link"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
    </div>

    <div class="app-topbar-account">
      <el-dropdown trigger="click" @command="handleAccountCommand">
        <button
          type="button"
          class="app-account-trigger"
          aria-haspopup="menu"
          :aria-label="`账号菜单：${displayName}`"
        >
          <span class="app-account-avatar" aria-hidden="true">{{ avatarInitials }}</span>
          <span class="app-account-copy">
            <strong>{{ displayName }}</strong>
            <small>{{ userEmail }}</small>
          </span>
          <span class="app-account-chevron" aria-hidden="true" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">账户设置</el-dropdown-item>
            <el-dropdown-item command="ai-provider">AI 设置</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>
