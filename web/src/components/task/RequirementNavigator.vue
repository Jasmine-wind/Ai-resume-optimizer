<script setup lang="ts">
import type { EvidenceRequirementItem } from '@/types/evidence-analysis'

const props = defineProps<{
  requirements: EvidenceRequirementItem[]
  selectedRequirementId: number | null
}>()

const emit = defineEmits<{
  select: [requirementId: number]
}>()

const statusLabel = (matchLevel: string) => {
  switch (matchLevel) {
    case 'MATCHED':
      return '已有优势'
    case 'PARTIAL_EVIDENCE':
      return '可强化表达'
    case 'NO_EVIDENCE':
      return '当前材料未体现'
    default:
      return '需要核对'
  }
}

const statusClass = (matchLevel: string) => {
  switch (matchLevel) {
    case 'MATCHED':
      return 'is-supported'
    case 'PARTIAL_EVIDENCE':
      return 'is-needs-edit'
    case 'NO_EVIDENCE':
      return 'is-gap'
    default:
      return 'is-unknown'
  }
}

const countByStatus = (matchLevel: string) =>
  props.requirements.filter((item) => item.matchLevel === matchLevel).length
</script>

<template>
  <aside class="requirements-rail" aria-label="岗位要求">
    <div class="rail-header">
      <div class="rail-title-line">
        <h2>岗位要求 · {{ requirements.length }}</h2>
        <span class="rail-count">{{ countByStatus('MATCHED') }} 已支持 · {{ countByStatus('PARTIAL_EVIDENCE') + countByStatus('NO_EVIDENCE') }} 待完善</span>
      </div>
    </div>

    <div v-if="requirements.length" class="requirement-list" role="list">
      <button
        v-for="(requirement, index) in requirements"
        :key="requirement.evidenceRequirementId"
        type="button"
        class="requirement-item"
        :class="[
          statusClass(requirement.matchLevel),
          { 'is-selected': requirement.evidenceRequirementId === selectedRequirementId },
        ]"
        :aria-pressed="requirement.evidenceRequirementId === selectedRequirementId"
        @click="emit('select', requirement.evidenceRequirementId)"
      >
        <span class="requirement-number">{{ String(index + 1).padStart(2, '0') }}</span>
        <span class="requirement-content">
          <strong>{{ requirement.requirementText }}</strong>
          <span class="requirement-meta">
            <span class="status-dot" :class="statusClass(requirement.matchLevel)" aria-hidden="true" />
            <span>{{ statusLabel(requirement.matchLevel) }}</span>
          </span>
        </span>
      </button>
    </div>

    <div v-else class="rail-empty" role="status">
      <strong>暂时没有逐条岗位要求</strong>
      <span>分析完成后，相关证据会显示在这里。</span>
    </div>


  </aside>
</template>

<style scoped>
.requirements-rail {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  background: var(--app-surface-soft);
  border-right: 1px solid var(--app-border-strong);
}

.rail-header {
  padding: 14px 18px 12px;
  border-bottom: 1px solid var(--app-border);
}

.rail-title-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.rail-title-line h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 14px;
  font-weight: 750;
  letter-spacing: -0.02em;
}


.rail-count,
.requirement-meta {
  font-family: var(--app-font-mono);
}

.rail-count {
  flex: 0 0 auto;
  color: var(--app-text-muted);
  font-size: 9px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.requirement-list {
  min-height: 0;
  overflow: auto;
  scrollbar-color: var(--app-scroll-thumb) transparent;
  scrollbar-width: thin;
}

.requirement-item {
  position: relative;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 8px;
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--app-border);
  padding: 11px 16px 10px 18px;
  color: var(--app-text);
  text-align: left;
  background: transparent;
  cursor: pointer;
  transition: background 180ms ease;
}

.requirement-item::before {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 2px;
  background: var(--app-primary);
  content: '';
  opacity: 0;
  transition: opacity 180ms ease;
}

.requirement-item:hover {
  background: var(--app-bg-soft);
}

.requirement-item:focus-visible {
  z-index: 1;
  outline: 2px solid var(--app-primary);
  outline-offset: -2px;
}

.requirement-item.is-selected {
  background: color-mix(in srgb, var(--app-primary-soft) 78%, var(--app-surface));
}

.requirement-item.is-supported:not(.is-selected) {
  background: color-mix(in srgb, var(--app-status-supported-soft) 36%, transparent);
}

.requirement-item.is-needs-edit:not(.is-selected) {
  background: color-mix(in srgb, var(--app-status-partial-soft) 30%, transparent);
}

.requirement-item.is-gap:not(.is-selected) {
  background: color-mix(in srgb, var(--app-status-gap-soft) 24%, transparent);
}

.requirement-item.is-selected::before {
  opacity: 1;
}

.requirement-number {
  display: inline-grid;
  width: 24px;
  height: 18px;
  margin-top: 1px;
  place-items: center;
  color: var(--app-text-muted);
  font-size: 9px;
  font-weight: 650;
}

.requirement-item.is-selected .requirement-number {
  color: var(--app-surface);
  background: var(--app-primary-active);
}

.requirement-content {
  min-width: 0;
}

.requirement-content strong {
  display: -webkit-box;
  overflow: hidden;
  margin-bottom: 5px;
  color: var(--app-text);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.requirement-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 0;
  color: var(--app-text-muted);
  font-size: 9px;
}

.requirement-item.is-selected .requirement-meta {
  color: var(--app-text-secondary);
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-text-muted);
}

.status-dot.is-supported {
  background: var(--app-success);
}

.status-dot.is-needs-edit {
  background: var(--app-warning);
}

.status-dot.is-gap {
  background: var(--app-primary);
}

.rail-empty {
  display: grid;
  gap: 7px;
  padding: 22px;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.rail-empty strong {
  color: var(--app-text);
  font-size: 13px;
}

@media (max-width: 1119px) {
  .requirements-rail {
    min-height: auto;
    border-right: 0;
    border-bottom: 1px solid var(--app-border-strong);
  }

  .rail-header {
    padding: 11px 14px 9px;
  }

  .rail-title-line {
    gap: 8px;
  }

  .rail-title-line h2 {
    font-size: 13px;
  }

  .rail-count {
    font-size: 8px;
  }

  .requirement-list {
    display: flex;
    overflow-x: auto;
    border-top: 1px solid var(--app-border);
  }

  .requirement-item {
    min-width: 0;
    padding: 10px 14px 9px 16px;
  }
}

</style>
