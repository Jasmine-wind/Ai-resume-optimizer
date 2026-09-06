<script setup lang="ts">
import ProductMark from '@/components/common/ProductMark.vue'
</script>

<template>
  <main class="auth-page">
    <header class="auth-topbar">
      <RouterLink to="/" class="auth-brand" aria-label="返回简历优化首页">
        <ProductMark size="md" />
        <strong>简历优化</strong>
      </RouterLink>
      <RouterLink to="/" class="auth-home-link">返回首页</RouterLink>
    </header>

    <section class="auth-shell">
      <section class="auth-form-panel">
        <slot />
      </section>
      <aside class="auth-context" aria-label="产品工作方式">
        <p class="auth-context-label">你将始终掌握决定权</p>
        <h2>从真实材料开始，准备一份更合适的简历。</h2>
        <p class="auth-context-copy">我们只把目标岗位与已确认的简历材料放在一起核对。</p>
        <ol class="auth-context-steps">
          <li>
            <span class="auth-context-node is-source" aria-hidden="true" />
            <div><strong>真实简历</strong><span>从你已有的经历开始</span></div>
          </li>
          <li>
            <span class="auth-context-node is-evidence" aria-hidden="true" />
            <div><strong>岗位证据</strong><span>看清哪里匹配、哪里值得修改</span></div>
          </li>
          <li>
            <span class="auth-context-node is-output" aria-hidden="true" />
            <div><strong>确认后导出</strong><span>你确认每一处修改，再生成 PDF</span></div>
          </li>
        </ol>
      </aside>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  color: var(--app-text);
  background: var(--app-bg);
}

.auth-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-4);
  min-height: var(--app-shell-header-height);
  padding: var(--app-space-2) max(var(--app-content-gutter), calc((100% - var(--app-layout-standard-max-width)) / 2));
  border-bottom: 1px solid var(--app-border);
  background: var(--app-surface);
}

.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--app-space-2);
  color: var(--app-text);
  font-size: 15px;
  font-weight: 750;
}

.auth-home-link {
  padding: 8px 10px;
  border-radius: var(--app-radius-sm);
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  font-weight: 650;
}

.auth-home-link:hover,
.auth-home-link:focus-visible {
  color: var(--app-text);
  background: var(--app-surface-soft);
}

.auth-shell {
  display: grid;
  grid-template-columns: minmax(360px, 440px) minmax(0, 0.88fr);
  gap: clamp(56px, 9vw, 136px);
  align-items: center;
  width: min(calc(100% - 64px), var(--app-landing-max-width));
  min-height: calc(100vh - var(--app-shell-header-height));
  margin: 0 auto;
  padding: 64px 0 80px;
}

.auth-context,
.auth-form-panel {
  min-width: 0;
}

.auth-form-panel {
  padding: 8px 0;
}

.auth-context {
  max-width: 520px;
  order: 1;
}

.auth-context-label {
  margin: 0 0 12px;
  color: var(--app-primary-active);
  font-family: var(--app-font-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.auth-context h2 {
  max-width: 24ch;
  margin: 0;
  color: var(--app-text);
  font-size: clamp(28px, 2.4vw, 36px);
  line-height: 1.16;
  letter-spacing: -0.035em;
}

.auth-context-copy {
  max-width: 38ch;
  margin: 18px 0 0;
  color: var(--app-text-secondary);
  font-size: 15px;
  line-height: 1.75;
}

.auth-context-steps {
  display: grid;
  gap: 0;
  max-width: 440px;
  margin: 34px 0 0;
  padding: 0;
  list-style: none;
}

.auth-context-steps li {
  position: relative;
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  gap: 12px;
  padding-bottom: 22px;
}

.auth-context-steps li:not(:last-child)::before {
  position: absolute;
  top: 14px;
  bottom: 0;
  left: 7px;
  width: 1px;
  background: var(--app-border-strong);
  content: '';
}

.auth-context-node {
  position: relative;
  z-index: 1;
  width: 14px;
  height: 14px;
  margin-top: 2px;
  border: 1px solid var(--app-primary);
  border-radius: 50%;
  background: var(--app-bg);
}

.auth-context-node.is-source {
  border-color: var(--app-accent);
  background: var(--app-accent);
}

.auth-context-node.is-output {
  background: var(--app-primary-soft);
}

.auth-context-steps div {
  display: grid;
  gap: 3px;
}

.auth-context-steps strong {
  color: var(--app-text);
  font-size: 15px;
  line-height: 1.35;
}

.auth-context-steps span:not(.auth-context-node) {
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.auth-form-panel {
  order: 0;
}

@media (max-width: 820px) {
  .auth-topbar {
    padding-right: var(--app-content-gutter-narrow);
    padding-left: var(--app-content-gutter-narrow);
  }

  .auth-shell {
    grid-template-columns: 1fr;
    gap: 32px;
    width: min(calc(100% - 32px), 560px);
    min-height: auto;
    padding: 40px 0 56px;
  }

  .auth-form-panel {
    order: 0;
  }

  .auth-context {
    order: 1;
  }
}
</style>
