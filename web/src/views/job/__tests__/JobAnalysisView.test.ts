// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import JobAnalysisView from '@/views/job/JobAnalysisView.vue'
import { getOptimizationAnalysisResult } from '@/api/job-analysis'
import type { EvidenceRequirementItem } from '@/types/evidence-analysis'

const push = vi.fn()
const elementPlusStubs = vi.hoisted(() => ({
  ElButton: {
    name: 'ElButton',
    props: ['disabled', 'loading', 'type', 'size'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { optimizationTaskId: '7' }, query: {} }),
  useRouter: () => ({ push }),
}))

vi.mock('element-plus', () => elementPlusStubs)
vi.mock('element-plus/es', () => elementPlusStubs)
vi.mock('@/api/job-analysis', () => ({
  getOptimizationAnalysisResult: vi.fn(),
}))

const resultMock = vi.mocked(getOptimizationAnalysisResult)

const requirement = (
  id: number,
  importance: string,
  matchLevel: string,
  evidenceCount?: number,
): EvidenceRequirementItem => ({
  evidenceRequirementId: id,
  requirementText: `要求 ${id}`,
  importance,
  matchLevel,
  conclusion: matchLevel === 'MATCHED' ? '已有支持' : '需要核对',
  suggestion: matchLevel === 'PARTIAL_EVIDENCE' ? '核对已有表达' : null,
  evidences:
    matchLevel === 'NO_EVIDENCE'
      ? []
      : Array.from({ length: evidenceCount ?? 1 }, (_, index) => ({
          requirementEvidenceId: id * 10 + index,
          sectionLabel: '工作经历',
          evidenceText: `材料 ${id}-${index + 1}`,
          supportLevel: matchLevel === 'MATCHED' ? 'SUFFICIENT' : 'PARTIAL',
        })),
})

const result = (requirements: EvidenceRequirementItem[]) => ({
  optimizationTaskId: 7,
  sourceResumeVersionId: 1,
  targetResumeVersionId: 2,
  jobTargetId: 3,
  status: 'SUCCESS',
  jobTitle: '后端工程师',
  resumeName: '我的简历',
  analysisMode: 'EVIDENCE' as const,
  evidenceAnalysis: {
    evidenceAnalysisId: 4,
    matchedCount: requirements.filter((item) => item.matchLevel === 'MATCHED').length,
    partialEvidenceCount: requirements.filter((item) => item.matchLevel === 'PARTIAL_EVIDENCE')
      .length,
    noEvidenceCount: requirements.filter((item) => item.matchLevel === 'NO_EVIDENCE').length,
    requirements,
  },
  legacyAnalysis: null,
})

const mountView = async (analysisResult: ReturnType<typeof result>) => {
  resultMock.mockResolvedValue(analysisResult)
  const wrapper = mount(JobAnalysisView, {
    global: {
      stubs: {
        ErrorState: { template: '<div><slot /></div>' },
        SkeletonBlock: { template: '<div />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('JobAnalysisView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    push.mockClear()
  })

  it('shares the task shell, selects the first actionable requirement, and navigates by requirement', async () => {
    const wrapper = await mountView(
      result([
        requirement(1, 'BONUS', 'NO_EVIDENCE'),
        requirement(2, 'REQUIRED', 'MATCHED'),
        requirement(3, 'BONUS', 'PARTIAL_EVIDENCE'),
        requirement(4, 'REQUIRED', 'NO_EVIDENCE'),
        requirement(5, 'REQUIRED', 'PARTIAL_EVIDENCE'),
      ]),
    )

    expect(wrapper.find('.task-identity h1').text()).toBe('后端工程师')
    expect(wrapper.find('.task-identity p').text()).toContain('我的简历')
    expect(wrapper.find('.task-workflow-step.is-active').text()).toContain('证据')
    expect(wrapper.find('.requirements-rail').text()).toContain('1 已支持')
    expect(wrapper.findAll('.requirement-item')).toHaveLength(5)
    expect(wrapper.find('.requirement-item.is-selected').text()).toContain('要求 5')
    expect(wrapper.find('.analysis-selected-requirement-bar strong').text()).toBe('要求 5')
    expect(wrapper.findAll('.analysis-detail-block')).toHaveLength(0)
    expect(wrapper.findAll('.analysis-evidence-quote')).toHaveLength(0)

    await wrapper.findAll('.requirement-item')[3]?.trigger('click')
    expect(wrapper.find('.analysis-selected-requirement-bar strong').text()).toBe('当前简历没有找到支持这项要求的内容')
    expect(wrapper.find('.analysis-boundary-disclosure').exists()).toBe(true)

    await wrapper.find('.analysis-selected-requirement-bar button').trigger('click')
    expect(push).toHaveBeenCalledWith({ path: '/workspace/7', query: { requirement: '4' } })
  })

  it('keeps a quiet detail view when every requirement is already matched', async () => {
    const wrapper = await mountView(result([requirement(1, 'REQUIRED', 'MATCHED')]))

    expect(wrapper.find('.requirements-rail').text()).toContain('1 已支持')
    expect(wrapper.find('.requirements-rail').text()).toContain('0 待处理')
    expect(wrapper.find('.requirement-item.is-selected').text()).toContain('要求 1')
    expect(wrapper.find('.analysis-selected-requirement-bar').text()).toContain('已有优势')
    expect(wrapper.findAll('.analysis-detail-block')).toHaveLength(0)
    expect(wrapper.find('.analysis-detail-empty').exists()).toBe(false)
  })
})
