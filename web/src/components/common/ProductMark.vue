<script setup lang="ts">
defineProps<{
  size?: 'sm' | 'md'
}>()
</script>

<template>
  <span class="product-mark" :class="`is-${size ?? 'md'}`" aria-hidden="true">
    <span class="product-mark-back" />
    <span class="product-mark-sheet">
      <span>CV</span>
      <i />
    </span>
  </span>
</template>

<style scoped>
.product-mark {
  --mark-size: 34px;
  position: relative;
  display: inline-block;
  width: var(--mark-size);
  height: var(--mark-size);
  flex: 0 0 auto;
  color: var(--app-primary-active);
}

.product-mark.is-sm {
  --mark-size: 32px;
}

.product-mark-back,
.product-mark-sheet {
  position: absolute;
  display: block;
  border: 1px solid var(--app-primary-subtle);
  border-radius: 7px;
  box-sizing: border-box;
}

.product-mark-back {
  inset: 2px 1px 1px 4px;
  background: var(--app-primary-soft);
}

.product-mark-sheet {
  inset: 0 4px 4px 0;
  overflow: hidden;
  background: var(--app-surface);
  box-shadow: 0 2px 4px color-mix(in srgb, var(--app-primary-active) 16%, transparent);
}

.product-mark-sheet::after {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 10px;
  height: 10px;
  border-bottom: 1px solid var(--app-primary-subtle);
  border-left: 1px solid var(--app-primary-subtle);
  border-radius: 0 0 0 5px;
  background: var(--app-primary-soft);
  content: '';
}

.product-mark-sheet > span {
  position: absolute;
  top: 8px;
  left: 6px;
  color: var(--app-primary-active);
  font-family: var(--app-font-sans);
  font-size: 10px;
  font-weight: 850;
  letter-spacing: -0.08em;
  line-height: 1;
}

.product-mark-sheet > i {
  position: absolute;
  right: 6px;
  bottom: 7px;
  left: 6px;
  height: 1px;
  background: var(--app-primary-subtle);
  box-shadow: 0 -4px 0 color-mix(in srgb, var(--app-primary-subtle) 72%, transparent);
}
</style>
