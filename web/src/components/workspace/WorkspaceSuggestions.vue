<script setup lang="ts">
import { computed } from 'vue'
import BulletSuggestionCard from '@/components/workspace/BulletSuggestionCard.vue'
import type { OptimizationAnalysisResult } from '@/types/job-analysis'
import type { ResumeDocument } from '@/types/resume-document'
import type { BulletSuggestController } from '@/utils/useBulletSuggest'
import {
  isKnownRequirementImportance,
  isKnownRequirementMatchLevel,
  sortEvidenceRequirements,
} from '@/utils/analysisPresentation'

const props = defineProps<{
  result: OptimizationAnalysisResult | null
  loading: boolean
  error: string | null
  selectedRequirementId?: number | null
  document?: ResumeDocument | null
  suggest?: BulletSuggestController | null
}>()

const emit = defineEmits<{
  retryLoad: []
  focusContext: []
  close: []
}>()

const requirements = computed(() => props.result?.evidenceAnalysis?.requirements ?? [])
const sortedRequirements = computed(() => sortEvidenceRequirements(requirements.value))

const priority = computed(() =>
  sortedRequirements.value.filter(
    (item) =>
      (item.importance === 'REQUIRED' || item.importance === 'BONUS') &&
      (item.matchLevel === 'PARTIAL_EVIDENCE' || item.matchLevel === 'NO_EVIDENCE'),
  ),
)
const matched = computed(() =>
  sortedRequirements.value.filter(
    (item) =>
      (item.importance === 'REQUIRED' || item.importance === 'BONUS') &&
      item.matchLevel === 'MATCHED',
  ),
)
const selected = computed(() => {
  const selectedId = props.selectedRequirementId
  if (selectedId) {
    const found = requirements.value.find((item) => item.evidenceRequirementId === selectedId)
    if (
      found &&
      isKnownRequirementImportance(found.importance) &&
      isKnownRequirementMatchLevel(found.matchLevel)
    ) {
      return found
    }
  }
  return priority.value[0] ?? matched.value[0] ?? null
})

const statusLabel = (value: string) => {
  switch (value) {
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

const statusClass = (value: string) => {
  switch (value) {
    case 'MATCHED':
      return 'is-matched'
    case 'PARTIAL_EVIDENCE':
      return 'is-partial'
    case 'NO_EVIDENCE':
      return 'is-missing'
    default:
      return 'is-unknown'
  }
}

const importanceLabel = (value: string) => (value === 'BONUS' ? '加分项' : '必需项')
const suggestionMode = computed(() => {
  const controller = props.suggest
  if (!controller || controller.phase.value === 'idle') return null
  if (controller.phase.value === 'ready' && controller.candidateStale.value) return 'stale' as const
  return controller.phase.value
})

const activeBulletId = computed(() => props.suggest?.activeBulletId.value ?? null)
const activeBulletText = computed(() => {
  const bulletId = activeBulletId.value
  if (!bulletId || !props.document) return ''
  for (const section of props.document.sections) {
    for (const entry of section.entries) {
      const bullet = entry.bullets.find((item) => item.id === bulletId)
      if (bullet) return bullet.text
    }
  }
  return ''
})
</script>

<template>
  <aside
    class="workspace-inspector"
    :aria-label="suggestionMode ? 'AI 优化检查器' : '要求证据检查器'"
    aria-live="polite"
  >
    <div class="inspector-scroll">
      <header class="inspector-header">
        <div v-if="suggestionMode" class="inspector-ref inspector-ref-ai">
          <span>AI 优化</span>
          <span class="inspector-ref-dot" aria-hidden="true" />
          <span>当前工作要点</span>
        </div>
        <div v-else class="inspector-ref">
          <span>岗位要求</span>
        </div>
        <button
          type="button"
          class="inspector-close"
          :aria-label="suggestionMode ? '收起 AI 优化' : '收起要求检查器'"
          @click="emit('close')"
        >
          收起
        </button>
      </header>

      <p v-if="loading" class="inspector-status-message" role="status">正在读取分析结论…</p>
      <div v-else-if="error" class="inspector-error" role="alert">
        <p>{{ error }}</p>
        <el-button size="small" @click="emit('retryLoad')">重新加载</el-button>
      </div>

      <template v-else-if="suggestionMode && props.suggest">
        <div class="inspector-ai-detail">
          <p class="inspector-context-label">受约束改写</p>
          <h2>只修改这一条简历内容</h2>
          <p class="inspector-ai-source">{{ activeBulletText || '当前简历中的工作要点' }}</p>
          <BulletSuggestionCard
            :mode="suggestionMode"
            :original-text="props.suggest.candidate.value?.originalText ?? activeBulletText"
            :suggested-text="props.suggest.candidate.value?.suggestedText ?? null"
            :reason="props.suggest.candidate.value?.reason ?? null"
            :reject-code="props.suggest.rejectInfo.value?.code ?? null"
            :reject-message="props.suggest.rejectInfo.value?.message ?? null"
            :error-message="props.suggest.errorMessage.value ?? null"
            @apply="props.suggest.apply()"
            @reject="props.suggest.reject()"
            @regenerate="props.suggest.regenerate()"
            @cancel="props.suggest.cancelCompose()"
            @submit-custom="(instruction: string) => activeBulletId && props.suggest?.submitCustom(activeBulletId, instruction)"
          />
        </div>
      </template>

      <template v-else-if="result?.evidenceAnalysis && selected">
        <div class="inspector-detail">
          <section class="inspector-block requirement-block">
            <div class="requirement-status-line">
              <span class="inspector-status" :class="statusClass(selected.matchLevel)">
                {{ statusLabel(selected.matchLevel) }}
              </span>
              <span class="inspector-importance">{{ importanceLabel(selected.importance) }}</span>
            </div>
            <h2>{{ selected.requirementText }}</h2>
          </section>

          <section class="inspector-block evidence-block">
            <div class="block-heading">
              <h3>简历中的依据</h3>
              <span v-if="selected.evidences.length" class="block-count">{{ selected.evidences.length }} 条</span>
            </div>
            <div v-if="selected.evidences.length" class="evidence-list">
              <button
                v-for="(evidence, index) in selected.evidences"
                :key="evidence.requirementEvidenceId"
                type="button"
                class="evidence-item"
                :class="{ 'is-primary': index === 0 }"
                @click="emit('focusContext')"
              >
                <div class="evidence-source">
                  <span class="evidence-type">{{ evidence.sectionLabel || '简历材料' }}</span>
                  <span>{{ evidence.supportLevel === 'SUFFICIENT' ? '足够支持' : '部分支持' }}</span>
                </div>
                <p>“{{ evidence.evidenceText }}”</p>
              </button>
            </div>
            <p v-else class="inspector-muted">当前材料中没有找到可引用的证据。</p>
          </section>

          <section class="inspector-block next-step-block">
            <h3>下一步 / 建议</h3>
            <p v-if="selected.matchLevel === 'MATCHED'">
              当前材料已经能够支持这项要求，无需为了匹配关键词额外修改。
            </p>
            <template v-else-if="selected.matchLevel === 'PARTIAL_EVIDENCE'">
              <p>已有相关经历，但目前的表达还不足以完整支撑这项要求。</p>
              <p><strong>建议：</strong>强化现有经历中与该要求直接相关的内容。</p>
            </template>
            <p v-else>
              当前简历没有找到可引用的材料。如果你确实有相关经历，可以手动补充；系统不会自动添加未经确认的事实。
            </p>
          </section>
        </div>
      </template>

      <template v-else-if="result?.legacyAnalysis">
        <section class="inspector-block inspector-section">
          <h3>历史分析</h3>
          <p class="inspector-muted">这是较早版本的分析结果，重新分析可以获得逐条 Evidence。</p>
        </section>
      </template>

      <p v-else class="inspector-quiet">暂无逐条证据分析，可以直接编辑简历内容。</p>
    </div>

  </aside>
</template>

<style scoped>
.workspace-inspector {
  display: flex;
  height: 100%;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  background: var(--app-surface-soft);
  border-left: 1px solid var(--app-border-strong);
}

.inspector-scroll {
  height: 0;
  min-height: 0;
  flex: 1 1 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  scrollbar-color: var(--app-scroll-thumb) transparent;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.inspector-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 18px 18px 0;
}

.inspector-ref {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px 7px;
  color: var(--app-text-secondary);
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
}

.inspector-ref-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--app-success);
}

.inspector-ref-ai .inspector-ref-dot {
  background: var(--app-ai);
}

.inspector-status,
.inspector-importance,
.inspector-status-message {
  font-size: 12px;
  font-weight: 700;
}

.inspector-importance {
  color: var(--app-text-muted);
  font-weight: 600;
}

.inspector-status.is-partial {
  color: var(--app-status-partial);
}

.inspector-status.is-missing {
  color: var(--app-status-gap);
}

.inspector-status.is-matched {
  color: var(--app-status-supported);
}

.inspector-status.is-unknown {
  color: var(--app-danger);
}

.inspector-close {
  border: 0;
  padding: 4px 0;
  color: var(--app-text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  background: transparent;
}

.inspector-close:hover,
.inspector-close:focus-visible {
  color: var(--app-primary-active);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.inspector-context-label {
  margin: 15px 18px 6px;
  color: var(--app-ai);
  font-family: var(--app-font-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.inspector-block {
  padding: 16px 18px;
  border-top: 1px solid var(--app-border-strong);
}

.requirement-block {
  padding-top: 14px;
  border-top: 0;
}

.requirement-status-line {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 8px;
}

.inspector-detail h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 18px;
  font-weight: 750;
  line-height: 1.35;
  letter-spacing: -0.02em;
}

.block-count,
.evidence-type {
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 11px;
  font-weight: 700;
}

.inspector-muted,
.inspector-quiet,
.next-step-block p {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.65;
}

.block-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 11px;
}

.block-heading h3,
.inspector-section h3 {
  margin: 0;
  color: var(--app-text);
  font-size: 13px;
  font-weight: 750;
}

.evidence-list {
  border-top: 1px solid var(--app-border);
}

.evidence-item {
  position: relative;
  display: block;
  width: 100%;
  padding: 10px 0 9px;
  border: 0;
  border-bottom: 1px solid var(--app-border);
  color: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.evidence-item:hover,
.evidence-item:focus-visible {
  background: color-mix(in srgb, var(--app-primary-soft) 42%, transparent);
}

.evidence-item:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: -2px;
}

.evidence-item.is-primary {
  padding-left: 11px;
}

.evidence-item.is-primary::before {
  position: absolute;
  top: 10px;
  bottom: 9px;
  left: 0;
  width: 2px;
  background: var(--app-primary);
  content: '';
}

.evidence-source {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 5px;
}

.evidence-source > span:last-child {
  color: var(--app-text-secondary);
  font-size: 12px;
}

.evidence-item p {
  margin: 0;
  color: var(--app-text);
  font-size: 12px;
  line-height: 1.6;
}

.next-step-block {
  display: grid;
  gap: 10px;
  background: color-mix(in srgb, var(--app-surface) 70%, var(--app-bg-soft));
}

.next-step-block h3 {
  margin: 0;
  color: var(--app-text);
  font-size: 13px;
  font-weight: 750;
}

.next-step-block strong {
  color: var(--app-text);
}

.inspector-status-message,
.inspector-quiet {
  margin: 22px 23px;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

.inspector-error {
  display: grid;
  gap: 9px;
  justify-items: start;
  margin: 18px 23px;
  padding: 12px;
  border: 1px solid var(--app-primary-subtle);
  background: var(--app-primary-soft);
}

.inspector-error p {
  margin: 0;
  color: var(--app-text);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 1250px) and (min-width: 1120px) {
  .inspector-header,
  .inspector-block {
    padding-right: 18px;
    padding-left: 18px;
  }

  .inspector-context-label {
    margin-right: 18px;
    margin-left: 18px;
  }
}

@media (max-width: 1119px) {
  .workspace-inspector {
    min-height: 0;
    border-top: 1px solid var(--app-border-strong);
    border-left: 0;
  }

  .inspector-scroll {
    overflow-x: hidden;
    overflow-y: auto;
  }

}
.inspector-ai-detail {
  padding: 15px 18px 24px;
  background: var(--app-ai-soft);
}

.inspector-ai-detail .inspector-context-label {
  margin: 0 0 6px;
}

.inspector-ai-detail h2 {
  margin: 0;
  color: var(--app-ai);
  font-size: 20px;
  line-height: 1.25;
}

.inspector-ai-source {
  overflow: hidden;
  margin: 9px 0 15px;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspector-ai-detail :deep(.bullet-suggestion) {
  margin-top: 0;
  border: 1px solid var(--app-ai-border);
  border-radius: var(--app-radius-sm);
}

@media (max-width: 1119px) {
  .inspector-ai-detail {
    padding-right: var(--app-space-4);
    padding-left: var(--app-space-4);
  }
}

</style>
