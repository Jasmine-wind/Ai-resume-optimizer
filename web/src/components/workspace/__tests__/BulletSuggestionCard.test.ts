// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import BulletSuggestionCard from '@/components/workspace/BulletSuggestionCard.vue'

const ElButton = {
  props: ['disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
}

describe('BulletSuggestionCard', () => {
  it('explains a fact-check rejection and keeps both next steps visible', () => {
    const wrapper = mount(BulletSuggestionCard, {
      props: {
        mode: 'rejected',
        originalText: '参与 Redis 优化。',
        rejectCode: 'NEW_TECHNOLOGY',
        rejectMessage: '改写引入了原文没有的技术名称',
      },
      global: { stubs: { ElButton } },
    })

    expect(wrapper.text()).toContain('这条建议没有通过事实校验')
    expect(wrapper.text()).toContain('引入原文没有的信息')
    expect(wrapper.text()).toContain('重新生成建议')
    expect(wrapper.text()).toContain('继续手工编辑')
  })

  it('shows original, suggested expression, deterministic diff and apply action together', async () => {
    const wrapper = mount(BulletSuggestionCard, {
      props: {
        mode: 'ready',
        originalText: '参与 Redis 缓存优化，完善监控与故障排查流程。',
        suggestedText: '参与 Redis 缓存优化，并完善监控与故障排查流程。',
        reason: '只补充连接词。',
      },
      global: { stubs: { ElButton } },
    })

    expect(wrapper.text()).toContain('原文')
    expect(wrapper.text()).toContain('建议版本')
    expect(wrapper.text()).toContain('差异')
    expect(wrapper.find('.diff-added').text()).toContain('并')
    expect(wrapper.get('button').text()).toBe('采纳')
  })
})
