// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import WorkspaceSuggestions from '@/components/workspace/WorkspaceSuggestions.vue'
import type { EvidenceRequirementItem } from '@/types/evidence-analysis'
import type { OptimizationAnalysisResult } from '@/types/job-analysis'
import type { ResumeDocument } from '@/types/resume-document'
import type { BulletSuggestController } from '@/utils/useBulletSuggest'

const requirement = (
  id: number,
  importance: string,
  matchLevel: string,
): EvidenceRequirementItem => ({
  evidenceRequirementId: id,
  requirementText: `要求 ${id}`,
  importance,
  matchLevel,
  conclusion: `结论 ${id}`,
  suggestion: matchLevel === 'PARTIAL_EVIDENCE' ? `建议 ${id}` : null,
  evidences:
    matchLevel === 'NO_EVIDENCE'
      ? []
      : [
          {
            requirementEvidenceId: id,
            sectionLabel: '工作经历',
            evidenceText: `证据 ${id}`,
            supportLevel: matchLevel === 'MATCHED' ? 'SUFFICIENT' : 'PARTIAL',
          },
        ],
})

const result: OptimizationAnalysisResult = {
  optimizationTaskId: 7,
  sourceResumeVersionId: 1,
  targetResumeVersionId: 2,
  jobTargetId: 3,
  status: 'SUCCESS',
  jobTitle: '后端工程师',
  resumeName: '我的简历',
  analysisMode: 'EVIDENCE',
  evidenceAnalysis: {
    evidenceAnalysisId: 4,
    matchedCount: 1,
    partialEvidenceCount: 1,
    noEvidenceCount: 1,
    requirements: [
      requirement(1, 'REQUIRED', 'NO_EVIDENCE'),
      requirement(2, 'REQUIRED', 'PARTIAL_EVIDENCE'),
      requirement(3, 'BONUS', 'MATCHED'),
    ],
  },
  legacyAnalysis: null,
}

describe('WorkspaceSuggestions', () => {
  it('keeps the inspector contextual instead of duplicating the full analysis list', () => {
    const wrapper = mount(WorkspaceSuggestions, {
      props: {
        result,
        loading: false,
        error: null,
        selectedRequirementId: 2,
      },
      global: {
        stubs: {
          ElButton: { template: '<button><slot /></button>' },
        },
      },
    })

    expect(wrapper.find('.inspector-detail').text()).toContain('要求 2')
    expect(wrapper.find('.inspector-detail').text()).toContain('证据 2')
    expect(wrapper.text()).not.toContain('要求 1')
    expect(wrapper.text()).not.toContain('要求 3')
    expect(wrapper.find('.inspector-counts').exists()).toBe(false)
    expect(wrapper.find('.inspector-list').exists()).toBe(false)
    expect(wrapper.findAll('.inspector-close')).toHaveLength(1)
    expect(wrapper.find('.inspector-footer').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('要求原文')
    expect(wrapper.text()).not.toContain('点击定位')
    expect(wrapper.findAll('.inspector-detail h2')).toHaveLength(1)
  })

  it('routes a READY advisory Apply event to the suggestion controller', async () => {
    const apply = vi.fn()
    const suggest = {
      phase: ref('ready'),
      candidateStale: ref(false),
      activeBulletId: ref('bullet-1'),
      candidate: ref({
        requestId: 'request-1',
        bulletId: 'bullet-1',
        baseRevision: 3,
        sequence: 1,
        originalText: '负责订单服务开发',
        suggestedText: '负责订单服务开发，并使用 Kafka 处理异步消息',
        reason: '补充 Kafka 相关技术描述，请确认真实性。',
        reviewCode: 'NEW_TECHNOLOGY',
        reviewMessage: '请确认内容真实。',
        modelName: 'test-model',
      }),
      rejectInfo: ref(null),
      errorMessage: ref(null),
      apply,
      reject: vi.fn(),
      regenerate: vi.fn(),
      cancelCompose: vi.fn(),
      submitCustom: vi.fn(),
    } as unknown as BulletSuggestController
    const document = {
      schemaVersion: 'RESUME_DOCUMENT_V1',
      basics: { name: '测试用户', contacts: [] },
      sections: [
        {
          id: 'experience',
          kind: 'EXPERIENCE',
          title: '工作经历',
          entries: [
            {
              id: 'entry-1',
              organization: '测试公司',
              role: '工程师',
              school: null,
              degree: null,
              major: null,
              startDate: null,
              endDate: null,
              location: null,
              group: null,
              skillItems: null,
              bullets: [{ id: 'bullet-1', text: '负责订单服务开发' }],
            },
          ],
        },
      ],
    } as ResumeDocument

    const wrapper = mount(WorkspaceSuggestions, {
      props: { result, loading: false, error: null, document, suggest },
      global: { stubs: { ElButton: { template: '<button><slot /></button>' } } },
    })

    expect(wrapper.text()).toContain('请确认内容真实。')
    const applyButton = wrapper.findAll('button').find((button) => button.text() === '确认并采纳')
    expect(applyButton).toBeDefined()
    await applyButton!.trigger('click')
    expect(apply).toHaveBeenCalledOnce()
  })

  it('focuses the resume immediately when an evidence row is activated', async () => {
    const wrapper = mount(WorkspaceSuggestions, {
      props: {
        result,
        loading: false,
        error: null,
        selectedRequirementId: 2,
      },
    })

    await wrapper.get('.evidence-item').trigger('click')
    expect(wrapper.emitted('focusContext')).toHaveLength(1)
  })
})
