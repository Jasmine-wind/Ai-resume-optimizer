<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'

defineProps<{
  current: 'profile' | 'ai-provider'
}>()
</script>

<template>
  <section class="settings-shell">
    <PageHeader eyebrow="账户设置" title="账户设置" description="管理你的个人资料和 AI 使用方式。" />
    <div class="settings-shell-layout">
      <nav class="settings-nav" aria-label="账户设置分类">
        <RouterLink
          to="/settings/profile"
          class="settings-nav-link"
          :class="{ 'is-active': current === 'profile' }"
          :aria-current="current === 'profile' ? 'page' : undefined"
        >
          个人资料
        </RouterLink>
        <RouterLink
          to="/settings/ai-provider"
          class="settings-nav-link"
          :class="{ 'is-active': current === 'ai-provider' }"
          :aria-current="current === 'ai-provider' ? 'page' : undefined"
        >
          AI 设置
        </RouterLink>
      </nav>
      <div class="settings-shell-content">
        <slot />
      </div>
    </div>
  </section>
</template>

<style scoped>
.settings-shell {
  width: min(100%, 1040px);
  margin: 0 auto;
}

.settings-shell :deep(.ui-page-header) {
  margin-bottom: var(--app-space-8);
}

.settings-shell-layout {
  display: grid;
  grid-template-columns: 190px minmax(0, 720px);
  gap: clamp(32px, 5vw, 72px);
  align-items: start;
}

.settings-nav {
  position: sticky;
  top: var(--app-space-6);
  display: grid;
  gap: 3px;
}

.settings-nav-link {
  min-height: 40px;
  display: flex;
  align-items: center;
  border-left: 2px solid transparent;
  padding: 0 var(--app-space-3);
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  font-weight: 650;
  text-decoration: none;
}

.settings-nav-link:hover,
.settings-nav-link:focus-visible {
  color: var(--app-text);
  background: var(--app-bg-soft);
}

.settings-nav-link:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.settings-nav-link.is-active {
  border-left-color: var(--app-primary);
  color: var(--app-text);
  background: var(--app-primary-soft);
}

.settings-shell-content {
  min-width: 0;
}

.settings-shell-content :deep(.settings-page) {
  gap: var(--app-section-spacing);
}

@media (max-width: 720px) {
  .settings-shell-layout {
    display: block;
  }

  .settings-nav {
    position: static;
    display: flex;
    gap: 0;
    overflow-x: auto;
    border-bottom: 1px solid var(--app-border-strong);
    margin-bottom: var(--app-space-6);
    scrollbar-width: none;
  }

  .settings-nav::-webkit-scrollbar {
    display: none;
  }

  .settings-nav-link {
    min-height: 42px;
    flex: 0 0 auto;
    border-right: 0;
    border-bottom: 2px solid transparent;
    border-left: 0;
    padding: 0 var(--app-space-4);
  }

  .settings-nav-link.is-active {
    border-bottom-color: var(--app-primary);
    background: transparent;
  }
}
</style>
