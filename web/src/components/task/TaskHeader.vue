<script setup lang="ts">
withDefaults(
  defineProps<{
    jobTitle: string
    resumeName: string
    activeStep: 'match' | 'evidence' | 'edit' | 'preview'
    backLabel: string
    statusText?: string
    statusTone?: string
    interactive?: boolean
  }>(),
  {
    statusText: '',
    statusTone: '',
    interactive: false,
  },
)

const emit = defineEmits<{
  back: []
  step: [step: 'match' | 'evidence' | 'edit' | 'preview']
}>()

const steps = [
  { id: 'match' as const, index: '01', label: '匹配' },
  { id: 'evidence' as const, index: '02', label: '证据' },
  { id: 'edit' as const, index: '03', label: '编辑' },
  { id: 'preview' as const, index: '04', label: '预览' },
]
</script>

<template>
  <header class="task-header">
    <div class="task-header-main">
      <div class="task-identity">
        <button type="button" class="task-back" @click="emit('back')">
          <span aria-hidden="true">←</span> {{ backLabel }}
        </button>
        <h1>{{ jobTitle }}</h1>
        <p><strong>{{ resumeName }}</strong><span aria-hidden="true">·</span>岗位定向任务</p>
      </div>
      <div class="task-header-actions">
        <slot name="actions" />
      </div>
    </div>

    <nav class="task-workflow" aria-label="岗位定向流程">
      <span class="task-workflow-label">任务流程</span>
      <span class="task-workflow-rule" aria-hidden="true" />
      <template v-for="(step, index) in steps" :key="step.id">
        <span v-if="index" class="task-workflow-divider" aria-hidden="true">→</span>
        <button
          type="button"
          class="task-workflow-step"
          :class="{ 'is-active': activeStep === step.id }"
          :aria-current="activeStep === step.id ? 'step' : undefined"
          :disabled="!interactive"
          @click="emit('step', step.id)"
        >
          <span class="task-workflow-index">{{ step.index }}</span>
          <span>{{ step.label }}</span>
        </button>
      </template>
      <span v-if="statusText" class="task-save-status" :class="statusTone" role="status">
        <span class="task-save-dot" aria-hidden="true" />
        {{ statusText }}
      </span>
    </nav>
  </header>
</template>

<style scoped>
.task-header {
  flex: 0 0 auto;
  background: var(--app-surface-soft);
  border-bottom: 1px solid var(--app-border-strong);
}

.task-header-main {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-6);
  padding: 10px var(--app-content-gutter);
}

.task-identity {
  min-width: 0;
}

.task-back {
  display: block;
  margin: 0 0 var(--app-space-1);
  border: 0;
  border-bottom: 1px solid transparent;
  padding: 0;
  color: var(--app-text-muted);
  font: inherit;
  font-size: var(--app-font-size-xs);
  cursor: pointer;
  background: transparent;
}

.task-back:hover,
.task-back:focus-visible {
  color: var(--app-primary-active);
  border-color: var(--app-primary);
}

.task-identity h1 {
  overflow: hidden;
  margin: 0;
  color: var(--app-text);
  font-size: 24px;
  font-weight: 750;
  line-height: var(--app-line-height-tight);
  letter-spacing: -0.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-identity p {
  display: flex;
  gap: var(--app-space-2);
  margin: var(--app-space-1) 0 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
}

.task-identity p strong {
  overflow: hidden;
  color: var(--app-text);
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-header-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--app-space-2);
}

.task-workflow {
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: var(--app-space-2);
  padding: 0 var(--app-content-gutter);
  overflow-x: auto;
  border-top: 1px solid var(--app-border);
  scrollbar-width: none;
}

.task-workflow::-webkit-scrollbar {
  display: none;
}

.task-workflow-label,
.task-workflow-step,
.task-save-status {
  flex: 0 0 auto;
  font-size: var(--app-font-size-xs);
}

.task-workflow-label {
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.task-workflow-rule {
  width: 1px;
  height: 18px;
  margin: 0 var(--app-space-2);
  background: var(--app-border-strong);
}

.task-workflow-step {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: var(--app-space-2);
  border: 0;
  border-bottom: 2px solid transparent;
  padding: 0 var(--app-space-1);
  color: var(--app-text-muted);
  font: inherit;
  font-weight: 700;
  background: transparent;
}

.task-workflow-step:not(:disabled) {
  cursor: pointer;
}

.task-workflow-step:not(:disabled):hover,
.task-workflow-step:not(:disabled):focus-visible {
  color: var(--app-text);
}

.task-workflow-step.is-active {
  color: var(--app-text);
  border-bottom-color: var(--app-primary);
}

.task-workflow-index {
  color: inherit;
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
}

.task-workflow-divider {
  color: var(--app-border-strong);
  font-size: 12px;
}

.task-save-status {
  display: inline-flex;
  align-items: center;
  gap: var(--app-space-2);
  margin-left: auto;
  color: var(--app-text-muted);
  white-space: nowrap;
}

.task-save-status.is-dirty,
.task-save-status.is-saving {
  color: var(--app-primary);
  font-weight: 700;
}

.task-save-status.is-failed,
.task-save-status.is-conflict {
  color: var(--app-warning);
  font-weight: 700;
}

.task-save-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-success);
}

.task-save-status.is-dirty .task-save-dot,
.task-save-status.is-saving .task-save-dot {
  background: var(--app-primary);
}

.task-save-status.is-failed .task-save-dot,
.task-save-status.is-conflict .task-save-dot {
  background: var(--app-warning);
}

@media (max-width: 960px) {
  .task-header-main,
  .task-workflow {
    padding-right: var(--app-content-gutter-narrow);
    padding-left: var(--app-content-gutter-narrow);
  }
}

@media (max-width: 640px) {
  .task-header-main {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    min-height: 0;
    align-items: end;
    gap: var(--app-space-2) var(--app-space-3);
    padding-top: 10px;
    padding-bottom: 10px;
  }

  .task-back {
    margin-bottom: 2px;
    font-size: 11px;
  }

  .task-identity h1 {
    font-size: 20px;
    line-height: 1.2;
  }

  .task-identity p {
    gap: 6px;
    margin-top: 2px;
    font-size: 12px;
  }

  .task-header-actions {
    align-self: end;
    justify-content: flex-end;
  }

  .task-header-actions .el-button {
    min-height: 32px;
    padding-right: 12px;
    padding-left: 12px;
  }

  .task-workflow-label,
  .task-workflow-rule {
    display: none;
  }

  .task-workflow {
    min-height: 34px;
    gap: var(--app-space-2);
  }

  .task-workflow-step {
    min-height: 26px;
  }

  .task-save-status {
    margin-left: var(--app-space-2);
    font-size: 11px;
  }
}
</style>
