<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getJobDirectionInsights } from '@/api/job-direction-insight'
import ErrorState from '@/components/common/ErrorState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import SkeletonBlock from '@/components/common/SkeletonBlock.vue'
import type {
  EvidenceCoverageLevel,
  JobDirectionCohort,
  JobDirectionRequirement,
  JobDirectionInsights,
} from '@/types/job-direction-insight'

const router = useRouter()
const insights = ref<JobDirectionInsights | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const selectedByCohort = ref<Record<string, string>>({})
const selectedCohortIndex = ref(0)

const load = async () => {
  loading.value = true
  error.value = null
  try {
    insights.value = await getJobDirectionInsights()
    selectedCohortIndex.value = 0
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '暂时无法读取方向洞察'
  } finally {
    loading.value = false
  }
}

const coverageLabel = (level: EvidenceCoverageLevel) => {
  switch (level) {
    case 'MATCHED':
      return '已有优势'
    case 'PARTIAL_EVIDENCE':
      return '可强化表达'
    case 'NO_EVIDENCE':
      return '当前材料未体现'
  }
}

const coverageClass = (level: EvidenceCoverageLevel) => `is-${level.toLowerCase()}`

const cohortKey = (cohort: JobDirectionCohort) => `${cohort.resumeId}-${cohort.newestAnalysisAt}`

const selectedCohort = computed(() => {
  const cohorts = insights.value?.cohorts ?? []
  return cohorts[selectedCohortIndex.value] ?? cohorts[0] ?? null
})

const selectedRequirement = (cohort: JobDirectionCohort) => {
  const selectedLabel = selectedByCohort.value[cohortKey(cohort)]
  return (
    cohort.commonRequirements.find((item) => item.label === selectedLabel) ??
    cohort.commonRequirements[0] ??
    null
  )
}

const selectRequirement = (cohort: JobDirectionCohort, requirement: JobDirectionRequirement) => {
  selectedByCohort.value = {
    ...selectedByCohort.value,
    [cohortKey(cohort)]: requirement.label,
  }
}

const windowText = (cohort: JobDirectionCohort) => {
  const start = cohort.windowStart?.slice(0, 10) ?? ''
  const end = cohort.newestAnalysisAt?.slice(0, 10) ?? ''
  return start && end ? `${start} 至 ${end}` : '最近 180 天'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="job-direction-insights">
    <PageHeader
      eyebrow="只读岗位视角"
      title="岗位方向洞察"
      description="系统只汇总同一份冻结简历材料下的正式岗位分析，帮助你看清近期岗位中反复出现的要求。"
    >
      <template #actions>
        <el-button @click="router.push('/app')">返回首页</el-button>
      </template>
    </PageHeader>

    <SkeletonBlock v-if="loading" title :rows="10" />

    <ErrorState
      v-else-if="error"
      title="方向洞察加载失败"
      :description="error"
      action-text="重新加载"
      @action="load"
    />

    <EmptyState
      v-else-if="!insights?.cohorts.length"
      title="继续按岗位分析，洞察会自然出现"
      description="当同一份冻结简历材料在最近 180 天内完成至少 8 个不同岗位的正式分析后，系统会在这里汇总常见要求。它不会替你推断现实能力，也不会改变任何已有分析结果。"
      action-text="分析新岗位"
      @action="router.push('/app')"
    />

    <div v-else class="insight-workspace">
      <p class="insight-boundary">
        以下支持情况来自当时冻结的简历材料，不是对你现实能力的判断；当前工作区的修改也不会回算这里的历史结果。
      </p>

      <div v-if="insights.cohorts.length > 1" class="cohort-switcher">
        <label for="insight-cohort">查看哪份简历</label>
        <select id="insight-cohort" v-model.number="selectedCohortIndex">
          <option v-for="(cohort, index) in insights.cohorts" :key="cohortKey(cohort)" :value="index">
            {{ cohort.resumeName }} · {{ cohort.sampleSize }} 个岗位
          </option>
        </select>
      </div>

      <article v-if="selectedCohort" :key="cohortKey(selectedCohort)" class="insight-cohort">
        <header class="cohort-header">
          <div class="cohort-identity">
            <p class="cohort-context-label">当前材料</p>
            <h2>{{ selectedCohort.resumeName }}</h2>
            <p>{{ selectedCohort.sampleSize }} 个不同岗位 · {{ windowText(selectedCohort) }}</p>
          </div>
          <dl class="cohort-facts">
            <div><dt>分析岗位</dt><dd>{{ selectedCohort.sampleSize }} 个</dd></div>
            <div><dt>最新分析</dt><dd>{{ selectedCohort.newestAnalysisAt.slice(0, 10) }}</dd></div>
          </dl>
        </header>

        <div v-if="selectedCohort.commonRequirements.length" class="insight-layout">
          <section class="common-requirements" aria-label="反复出现的岗位要求">
            <header class="insight-section-header">
              <div>
                <p class="insight-section-label">岗位要求</p>
                <h3>反复出现的要求</h3>
              </div>
              <span>{{ selectedCohort.commonRequirements.length }} 条</span>
            </header>
            <div class="insight-requirement-list" role="list">
              <button
                v-for="requirement in selectedCohort.commonRequirements"
                :key="requirement.label"
                type="button"
                class="insight-requirement-row"
                :class="{ 'is-selected': selectedRequirement(selectedCohort)?.label === requirement.label }"
                @click="selectRequirement(selectedCohort, requirement)"
              >
                <span class="insight-requirement-name">{{ requirement.label }}</span>
                <span class="insight-requirement-frequency">
                  {{ requirement.occurrenceCount }} / {{ requirement.sampleSize }} 个岗位出现
                </span>
                <span class="insight-requirement-distribution">
                  <span v-if="requirement.matchedCount" class="is-matched">已有优势 {{ requirement.matchedCount }}</span>
                  <span v-if="requirement.partialEvidenceCount" class="is-partial">可强化表达 {{ requirement.partialEvidenceCount }}</span>
                  <span v-if="requirement.noEvidenceCount" class="is-missing">当前材料未体现 {{ requirement.noEvidenceCount }}</span>
                </span>
              </button>
            </div>
          </section>

          <aside v-if="selectedRequirement(selectedCohort)" class="insight-source-trace" aria-label="来源与证据">
            <header class="insight-section-header">
              <div>
                <p class="insight-section-label">来源与证据</p>
                <h3>{{ selectedRequirement(selectedCohort)?.label }}</h3>
              </div>
              <span>{{ selectedRequirement(selectedCohort)?.sources.length }} 个来源</span>
            </header>

            <section class="trace-distribution">
              <span class="trace-label">当时材料的支持情况</span>
              <div class="trace-counts">
                <span class="is-matched">已有优势 {{ selectedRequirement(selectedCohort)?.matchedCount }}</span>
                <span class="is-partial">可强化表达 {{ selectedRequirement(selectedCohort)?.partialEvidenceCount }}</span>
                <span class="is-missing">当前材料未体现 {{ selectedRequirement(selectedCohort)?.noEvidenceCount }}</span>
              </div>
            </section>

            <section class="trace-sources">
              <div class="trace-sources-heading">
                <span class="trace-label">来源</span>
                <span>只读引用</span>
              </div>
              <article v-for="(source, index) in selectedRequirement(selectedCohort)?.sources" :key="source.evidenceRequirementId" class="trace-source">
                <div class="trace-source-heading">
                  <span :class="['trace-status', coverageClass(source.matchLevel)]">{{ coverageLabel(source.matchLevel) }}</span>
                  <span>来源 {{ String(index + 1).padStart(2, '0') }}</span>
                </div>
                <p>{{ source.requirementText }}</p>
                <blockquote v-for="evidence in source.evidences" :key="evidence.requirementEvidenceId">
                  <span v-if="evidence.sectionLabel">{{ evidence.sectionLabel }} · </span>“{{ evidence.evidenceText }}”
                </blockquote>
                <small v-if="!source.evidences.length">当前冻结材料没有可引用的证据。</small>
              </article>
            </section>
          </aside>
        </div>
        <p v-else class="no-common-requirements">
          这些岗位还没有一个在至少一半样本中出现的共同要求。
        </p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.job-direction-insights {
  display: grid;
  gap: var(--app-section-spacing);
}

.insight-workspace {
  display: grid;
  gap: var(--app-space-5);
}

.insight-boundary {
  max-width: 76ch;
  margin: 0;
  border-top: 1px solid var(--app-border);
  border-bottom: 1px solid var(--app-border);
  padding: var(--app-space-3) 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.cohort-switcher {
  display: flex;
  align-items: center;
  gap: var(--app-space-3);
  max-width: 520px;
}

.cohort-switcher label {
  flex: 0 0 auto;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  font-weight: 650;
}

.cohort-switcher select {
  min-width: 0;
  max-width: 100%;
  border: 1px solid var(--app-border-strong);
  border-radius: var(--app-radius-sm);
  padding: 8px 32px 8px 10px;
  color: var(--app-text);
  font: inherit;
  background: var(--app-surface);
}

.cohort-context-label {
  margin: 0;
  color: var(--app-text-muted) !important;
  font-size: var(--app-font-size-xs) !important;
  font-weight: 700;
}

.insight-cohort {
  display: grid;
  gap: var(--app-space-5);
  border-top: 1px solid var(--app-border-strong);
  border-bottom: 1px solid var(--app-border-strong);
  padding: var(--app-space-5) 0 var(--app-space-8);
}

.cohort-header,
.insight-section-header,
.cohort-facts,
.trace-source-heading,
.trace-sources-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--app-space-4);
}

.cohort-identity {
  display: grid;
  gap: var(--app-space-1);
}

.cohort-identity h2,
.insight-section-header h3 {
  margin: 0;
  color: var(--app-text);
}

.cohort-identity h2 {
  font-size: 22px;
  line-height: var(--app-line-height-tight);
}

.cohort-identity p,
.no-common-requirements {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.cohort-facts {
  flex: 0 0 auto;
  gap: var(--app-space-5);
  margin: 0;
}

.cohort-facts div {
  display: grid;
  gap: var(--app-space-1);
}

.cohort-facts dt,
.insight-section-label,
.trace-label,
.trace-sources-heading > span:last-child {
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.cohort-facts dd {
  margin: 0;
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
  font-weight: 700;
}

.insight-layout {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 0.56fr);
  gap: var(--app-space-8);
  border-top: 1px solid var(--app-border);
  padding-top: var(--app-space-5);
}

.common-requirements,
.insight-source-trace {
  min-width: 0;
}

.insight-section-header {
  align-items: baseline;
  padding-bottom: var(--app-space-3);
  border-bottom: 1px solid var(--app-border-strong);
}

.insight-section-header > span {
  color: var(--app-text-muted);
  font-size: var(--app-font-size-xs);
}

.insight-section-header h3 {
  margin-top: var(--app-space-1);
  font-size: 18px;
}

.insight-requirement-list {
  border-bottom: 1px solid var(--app-border);
}

.insight-requirement-row {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--app-space-1) var(--app-space-3);
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--app-border);
  padding: var(--app-space-4) var(--app-space-3);
  color: var(--app-text);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.insight-requirement-row:hover,
.insight-requirement-row:focus-visible {
  background: var(--app-surface-soft);
}

.insight-requirement-row::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 2px;
  background: var(--app-primary);
  content: '';
  opacity: 0;
}

.insight-requirement-row.is-selected {
  background: var(--app-primary-soft);
}

.insight-requirement-row.is-selected::before {
  opacity: 1;
}

.insight-requirement-name {
  min-width: 0;
  color: var(--app-text);
  font-size: var(--app-font-size-md);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.insight-requirement-frequency {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
}

.insight-requirement-distribution {
  display: flex;
  flex-wrap: wrap;
  grid-column: 1 / -1;
  gap: var(--app-space-3);
  color: var(--app-text-muted);
  font-size: var(--app-font-size-xs);
}

.is-matched {
  color: var(--app-success);
}

.is-partial {
  color: var(--app-warning);
}

.is-missing {
  color: var(--app-primary-active);
}

.insight-source-trace {
  border-left: 1px solid var(--app-border-strong);
  padding-left: var(--app-space-6);
}

.trace-distribution,
.trace-sources {
  display: grid;
  gap: var(--app-space-3);
  border-bottom: 1px solid var(--app-border);
  padding: var(--app-space-4) 0;
}

.trace-counts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--app-space-3);
  font-size: var(--app-font-size-xs);
  font-weight: 700;
}

.trace-source {
  display: grid;
  gap: var(--app-space-2);
  border-top: 1px solid var(--app-border-soft);
  padding-top: var(--app-space-3);
}

.trace-source-heading {
  align-items: baseline;
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
}

.trace-status.is-matched {
  color: var(--app-success);
}

.trace-status.is-partial_evidence {
  color: var(--app-warning);
}

.trace-status.is-no_evidence {
  color: var(--app-primary-active);
}

.trace-source p,
.trace-source small {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
}

.trace-source p {
  color: var(--app-text);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.trace-source blockquote {
  margin: 0;
  border-left: 1px solid var(--app-primary);
  padding-left: var(--app-space-3);
  color: var(--app-text);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
  overflow-wrap: anywhere;
}

.no-common-requirements {
  padding: var(--app-space-3) 0;
}

@media (max-width: 900px) {
  .insight-layout {
    display: block;
  }

  .insight-source-trace {
    margin-top: var(--app-space-6);
    border-top: 1px solid var(--app-border-strong);
    border-left: 0;
    padding: var(--app-space-5) 0 0;
  }
}

@media (max-width: 640px) {
  .cohort-switcher {
    align-items: stretch;
    flex-direction: column;
  }

  .cohort-switcher select {
    width: 100%;
  }

  .cohort-header {
    flex-direction: column;
  }

  .cohort-facts {
    width: 100%;
    justify-content: space-between;
  }

  .insight-requirement-row {
    grid-template-columns: 1fr;
  }

  .insight-requirement-frequency {
    grid-column: 1;
  }
}
</style>
