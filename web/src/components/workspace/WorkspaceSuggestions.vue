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
  locationLabel?: string | null
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
const evidenceLocation = (sectionLabel: string | null) => sectionLabel || '简历材料'

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
        <div v-if="suggestionMode" class="inspector-ref">
          <span>AI 优化</span>
          <span class="inspector-ref-dot" aria-hidden="true" />
          <span>当前工作要点</span>
        </div>
        <div v-else class="inspector-ref">
          <span v-if="selected">当前修改上下文 · {{ String(requirements.indexOf(selected) + 1).padStart(2, '0') }}</span>
          <span class="inspector-ref-dot" aria-hidden="true" />
          <span v-if="selected" :class="['inspector-status', statusClass(selected.matchLevel)]">
            {{ statusLabel(selected.matchLevel) }}
          </span>
          <span v-if="selected" class="inspector-importance">{{ importanceLabel(selected.importance) }}</span>
          <span v-else>岗位分析</span>
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
          <p v-if="props.locationLabel" class="inspector-target-location">对应位置：{{ props.locationLabel }}</p>
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
        <p class="inspector-context-label">岗位要求</p>
        <h2>{{ selected.requirementText }}</h2>

        <section class="inspector-block jd-block">
          <span class="block-label">要求原文</span>
          <blockquote>“{{ selected.requirementText }}”</blockquote>
          <p class="inspector-note">这条结论来自本次岗位分析时冻结的 JD。</p>
        </section>

        <section class="inspector-block evidence-block">
          <div class="block-heading">
            <h3>来自简历</h3>
            <span class="block-count">{{ selected.evidences.length }} 条关联</span>
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
                <span class="evidence-type">{{ evidenceLocation(evidence.sectionLabel) }}</span>
                <span>{{ evidence.supportLevel === 'SUFFICIENT' ? '足够支持' : '部分支持' }}</span>
              </div>
              <p>“{{ evidence.evidenceText }}”</p>
              <span class="evidence-location">来自已确认的简历材料 · 点击定位</span>
            </button>
          </div>
          <p v-else class="inspector-muted">当前材料中没有找到可引用的证据。</p>
        </section>

        <section class="inspector-block gap-block">
          <span class="block-label">{{ selected.matchLevel === 'MATCHED' ? '核对结论' : '需要补足' }}</span>
          <div class="gap-content">
            <span class="gap-marker" :class="statusClass(selected.matchLevel)" aria-hidden="true" />
            <p>{{ selected.conclusion || '当前材料暂时无法支持这项要求。' }}</p>
          </div>
          <p v-if="selected.matchLevel === 'NO_EVIDENCE'" class="inspector-boundary">
            当前材料未体现不代表你没有这项经历。只有在确有真实经历时，才手动补充到简历；系统不会自动加入。
          </p>
          <p v-else-if="selected.matchLevel === 'PARTIAL_EVIDENCE'" class="inspector-boundary">
            已有相关经历，但当前简历中的表达还不够充分。可以强化已有材料的表达，不会自动加入新事实。
          </p>
        </section>

        <section class="inspector-block suggestion-block">
          <div class="block-heading suggestion-heading">
            <h3>建议修改</h3>
            <span class="block-count">保留真实材料</span>
          </div>
          <p class="suggestion-intro">{{ selected.suggestion || '当前材料已足够支持这项要求，无需为了匹配关键词额外增加事实。' }}</p>
          <div class="suggestion-action-note">
            <span class="action-mark" aria-hidden="true">↗</span>
            <span>选择中央简历中的具体内容，在该行旁使用“优化”查看受约束改写。</span>
          </div>
          <button type="button" class="inspector-focus-button" @click="emit('focusContext')">
            定位到对应简历内容
          </button>
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

    <footer v-if="selected && !suggestionMode" class="inspector-footer">
      <span class="footer-note">分析结论只对应当前冻结材料</span>
      <button type="button" class="next-button" @click="emit('close')">收起检查器</button>
    </footer>
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
  font-size: 9px;
  font-weight: 650;
  letter-spacing: 0.05em;
}

.inspector-ref-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--app-success);
}

.inspector-status,
.inspector-importance,
.inspector-status-message {
  font-size: 11px;
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
  font-size: 11px;
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
  margin: 15px 18px 5px;
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.inspector-detail > h2 {
  margin: 0 18px 16px;
  color: var(--app-text);
  font-size: 20px;
  font-weight: 750;
  line-height: 1.22;
  letter-spacing: -0.035em;
}

.inspector-block {
  padding: 13px 18px 14px;
  border-top: 1px solid var(--app-border-strong);
}

.block-label,
.block-count,
.evidence-type {
  color: var(--app-text-muted);
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.jd-block blockquote {
  margin: 8px 0 7px;
  padding-left: 12px;
  color: var(--app-text);
  border-left: 1px solid var(--app-primary);
  font-size: 12px;
  line-height: 1.55;
}

.inspector-note,
.inspector-muted,
.inspector-quiet,
.inspector-boundary,
.suggestion-intro,
.suggestion-action-note,
.inspector-target-location {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 11px;
  line-height: 1.6;
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
  font-size: 10px;
}

.evidence-item p {
  margin: 0 0 5px;
  color: var(--app-text);
  font-size: 11px;
  line-height: 1.5;
}

.evidence-location {
  color: var(--app-text-muted);
  font-size: 10px;
}

.gap-content {
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr);
  gap: 10px;
  margin-top: 8px;
}

.gap-marker {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--app-primary);
}

.gap-marker.is-matched {
  background: var(--app-success);
}

.inspector-boundary {
  margin-top: 10px;
  color: var(--app-text-muted);
}

.suggestion-block {
  background: var(--app-ai-surface);
}

.suggestion-intro {
  color: var(--app-text);
  font-size: 12px;
}

.suggestion-action-note {
  display: flex;
  gap: 7px;
  margin-top: 12px;
  color: var(--app-text-secondary);
}

.inspector-target-location {
  margin: 8px 18px 14px;
  padding: 8px 10px;
  border-left: 2px solid var(--app-primary);
  color: var(--app-text-secondary);
  font-size: 11px;
  line-height: 1.55;
  background: var(--app-primary-soft);
}

.inspector-focus-button {
  margin-top: 13px;
  border: 0;
  padding: 0;
  color: var(--app-primary-active);
  font: inherit;
  font-size: 11px;
  font-weight: 700;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.inspector-focus-button:hover,
.inspector-focus-button:focus-visible {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.action-mark {
  flex: 0 0 auto;
  color: var(--app-primary);
  font-size: 14px;
  line-height: 1.2;
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

.inspector-footer {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding: 12px 18px 12px 23px;
  background: var(--app-surface-soft);
  border-top: 1px solid var(--app-border-strong);
}

.footer-note {
  color: var(--app-text-muted);
  font-size: 10px;
}

.next-button {
  border: 0;
  border-bottom: 1px solid var(--app-text);
  padding: 4px 0;
  color: var(--app-text);
  font: inherit;
  font-size: 10px;
  font-weight: 700;
  background: transparent;
  cursor: pointer;
}

.next-button:hover,
.next-button:focus-visible {
  color: var(--app-primary-active);
  border-color: var(--app-primary);
}

@media (max-width: 1250px) and (min-width: 1120px) {
  .inspector-header,
  .inspector-block {
    padding-right: 18px;
    padding-left: 18px;
  }

  .inspector-context-label,
  .inspector-detail > h2 {
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

  .inspector-footer {
    position: sticky;
    bottom: 0;
  }
}
.inspector-ai-detail {
  padding: 15px 18px 24px;
}

.inspector-ai-detail .inspector-context-label {
  margin: 0 0 6px;
}

.inspector-ai-detail h2 {
  margin: 0;
  color: var(--app-text);
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
  border-radius: var(--app-radius-sm);
}

@media (max-width: 1119px) {
  .inspector-ai-detail {
    padding-right: var(--app-space-4);
    padding-left: var(--app-space-4);
  }
}

</style>
