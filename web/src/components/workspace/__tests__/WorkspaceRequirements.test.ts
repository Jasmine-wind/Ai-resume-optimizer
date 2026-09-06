// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import WorkspaceRequirements from '@/components/workspace/WorkspaceRequirements.vue'
import type { EvidenceRequirementItem } from '@/types/evidence-analysis'

const requirement = (
  id: number,
  matchLevel: string,
  evidenceCount: number,
): EvidenceRequirementItem => ({
  evidenceRequirementId: id,
  requirementText: `岗位要求 ${id}`,
  importance: 'REQUIRED',
  matchLevel,
  conclusion: `结论 ${id}`,
  suggestion: null,
  evidences: Array.from({ length: evidenceCount }, (_, index) => ({
    requirementEvidenceId: id * 10 + index,
    sectionLabel: '工作经历',
    evidenceText: `证据 ${id}`,
    supportLevel: matchLevel === 'MATCHED' ? 'SUFFICIENT' : 'PARTIAL',
  })),
})

describe('WorkspaceRequirements', () => {
  it('maps formal evidence states to navigable requirement rows', async () => {
    const select = vi.fn()
    const wrapper = mount(WorkspaceRequirements, {
      props: {
        selectedRequirementId: 2,
        requirements: [
          requirement(1, 'MATCHED', 1),
          requirement(2, 'PARTIAL_EVIDENCE', 1),
          requirement(3, 'NO_EVIDENCE', 0),
        ],
        onSelect: select,
      },
    })

    expect(wrapper.findAll('.requirement-item')).toHaveLength(3)
    expect(wrapper.findAll('.requirement-item')[0].text()).toContain('已有优势')
    expect(wrapper.findAll('.requirement-item')[1].text()).toContain('可强化表达')
    expect(wrapper.findAll('.requirement-item')[2].text()).toContain('当前材料未体现')
    expect(wrapper.findAll('.requirement-status-label')[0].classes()).toContain('is-supported')
    expect(wrapper.findAll('.requirement-status-label')[1].classes()).toContain('is-needs-edit')
    expect(wrapper.findAll('.requirement-status-label')[2].classes()).toContain('is-gap')
    expect(wrapper.find('.rail-count').text()).toContain('待处理')
    expect(wrapper.findAll('.requirement-item')[1].classes()).toContain('is-selected')

    await wrapper.findAll('.requirement-item')[2].trigger('click')
    expect(select).toHaveBeenCalledWith(3)
  })
})
