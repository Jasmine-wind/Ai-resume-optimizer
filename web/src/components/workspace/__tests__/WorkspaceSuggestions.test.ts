// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkspaceSuggestions from '@/components/workspace/WorkspaceSuggestions.vue'
import type { EvidenceRequirementItem } from '@/types/evidence-analysis'
import type { OptimizationAnalysisResult } from '@/types/job-analysis'

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
