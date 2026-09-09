// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import ResumeEditor from '@/components/workspace/ResumeEditor.vue'
import type { ResumeDocument } from '@/types/resume-document'
import type { BulletSuggestController } from '@/utils/useBulletSuggest'

const confirmEntryDelete = vi.hoisted(() => vi.fn())

const elementPlusStubs = vi.hoisted(() => ({
  ElInput: {
    name: 'ElInput',
    props: ['modelValue', 'type', 'placeholder', 'maxlength'],
    emits: ['update:modelValue'],
    template:
      '<textarea :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)"></textarea>',
  },
  ElButton: {
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  ElDropdown: {
    name: 'ElDropdown',
    emits: ['command'],
    template: '<div><slot /></div>',
  },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { template: '<div><slot /></div>' },
  ElMessageBox: { confirm: confirmEntryDelete },
}))

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn() },
  ...elementPlusStubs,
}))

// unplugin-vue-components 将模板组件从 element-plus/es 局部导入；两处均替换为稳定测试 stub。
vi.mock('element-plus/es', () => elementPlusStubs)

const makeSuggest = (activeBulletId: string | null, busy = false) => ({
  activeBulletId: ref(activeBulletId),
  busy: ref(busy),
  candidate: ref(null),
  suggest: vi.fn(),
  startCustomCompose: vi.fn(),
}) as unknown as BulletSuggestController

const makeDocument = (): ResumeDocument => ({
  schemaVersion: 'RESUME_DOCUMENT_V1',
  basics: {
    name: '张晓测试',
    contacts: [{ id: 'c1', type: 'EMAIL', label: '邮箱', value: 'test@example.com' }],
  },
  sections: [
    {
      id: 's1',
      kind: 'EXPERIENCE',
      title: '工作经历',
      entries: [
        {
          id: 'e1',
          organization: '某科技有限公司',
          role: '后端开发工程师',
          school: null,
          degree: null,
          major: null,
          startDate: '2022.07',
          endDate: '至今',
          location: null,
          group: null,
          skillItems: null,
          bullets: [{ id: 'b1', text: '负责订单系统后端接口开发' }],
        },
      ],
    },
  ],
})

describe('ResumeEditor', () => {
  it('keeps the document focused by removing permanent editor microcopy and counts', () => {
    const wrapper = mount(ResumeEditor, { props: { document: makeDocument() } })

    expect(wrapper.find('.resume-page-meta').exists()).toBe(false)
    expect(wrapper.find('.resume-page-footer').exists()).toBe(false)
    expect(wrapper.find('.section-entry-count').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('添加职位、时间或地点')
  })

  it('reopens an existing inspector from an active bullet error state without generating', async () => {
    const suggest = makeSuggest('b1')
    const wrapper = mount(ResumeEditor, {
      props: { document: makeDocument(), suggest, suggestEnabled: true },
    })

    const action = wrapper.get('.bullet-suggest-button')
    expect(action.text()).toBe('查看 AI 建议')
    await action.trigger('click')

    expect(wrapper.emitted('reopenInspector')).toHaveLength(1)
    expect(suggest.suggest).not.toHaveBeenCalled()
  })

  it('reopens an existing ready inspector without discarding its candidate', async () => {
    const suggest = makeSuggest('b1')
    const candidate = { requestId: 'request-1', suggestedText: '候选文本' }
    suggest.candidate.value = candidate as never
    const wrapper = mount(ResumeEditor, {
      props: { document: makeDocument(), suggest, suggestEnabled: true },
    })

    await wrapper.get('.bullet-suggest-button').trigger('click')

    expect(wrapper.emitted('reopenInspector')).toHaveLength(1)
    expect(suggest.candidate.value).toEqual(candidate)
    expect(suggest.suggest).not.toHaveBeenCalled()
  })

  it('disables AI actions while a suggestion is requesting', async () => {
    const suggest = makeSuggest('b1', true)
    const wrapper = mount(ResumeEditor, {
      props: { document: makeDocument(), suggest, suggestEnabled: true },
    })

    const action = wrapper.get('.bullet-suggest-button')
    expect(action.attributes('disabled')).toBeDefined()
    await action.trigger('click')
    expect(wrapper.emitted('reopenInspector')).toBeUndefined()
    expect(suggest.suggest).not.toHaveBeenCalled()
  })

  it('keeps a different bullet on the normal suggest flow when another bullet is active', async () => {
    const document = makeDocument()
    document.sections[0]!.entries[0]!.bullets.push({ id: 'b2', text: '负责缓存设计' })
    const suggest = makeSuggest('b1')
    const wrapper = mount(ResumeEditor, {
      props: { document, suggest, suggestEnabled: true },
    })

    const dropdowns = wrapper.findAllComponents({ name: 'ElDropdown' })
    await dropdowns[1]!.vm.$emit('command', 'SIMPLIFY')

    expect(suggest.suggest).toHaveBeenCalledWith('b2', 'SIMPLIFY')
    expect(wrapper.emitted('reopenInspector')).toBeUndefined()
  })

  it('edits a bullet when the document prop is a reactive proxy', async () => {
    // 与 WorkspacePanel 一致：ref 深层响应式会把 document 变成 Proxy。
    // structuredClone 无法克隆 Proxy，编辑必须保持可用（Phase 8 浏览器回归缺陷）。
    const draft = ref<ResumeDocument>(makeDocument())
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, {
      props: {
        document: draft.value,
        onChange,
      },
    })

    const bulletInput = wrapper
      .findAllComponents({ name: 'ElInput' })
      .find((item) => item.props('type') === 'textarea')
    expect(bulletInput).toBeTruthy()
    const updateBullet = bulletInput!.vm.$.vnode.props?.['onUpdate:modelValue'] as
      | ((value: string) => void)
      | undefined
    expect(updateBullet).toBeTypeOf('function')

    updateBullet!('负责订单系统后端接口开发（测试补充）')
    await nextTick()

    expect(onChange).toHaveBeenCalledTimes(1)
    const changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed.sections[0].entries[0].bullets[0].text).toBe(
      '负责订单系统后端接口开发（测试补充）',
    )
    // 发出的是纯对象，而不是响应式代理或原引用。
    expect(changed).not.toBe(draft.value)
  })

  it('keeps identity editing contextual instead of showing a permanent form field', async () => {
    const wrapper = mount(ResumeEditor, { props: { document: makeDocument() } })

    expect(wrapper.find('.identity-name').exists()).toBe(true)
    expect(wrapper.find('.identity-name-input').exists()).toBe(false)

    await wrapper.get('.identity-name').trigger('click')
    expect(wrapper.find('.identity-name-input').exists()).toBe(true)
  })

  it('keeps entry editing direct from the title and confirms destructive deletion', async () => {
    const editWrapper = mount(ResumeEditor, { props: { document: makeDocument() } })
    expect(editWrapper.get('.entry-delete-action').classes()).toContain('is-heading-delete')
    await editWrapper.get('.entry-title-display').trigger('click')
    expect(editWrapper.get('.entry-inline-editor').exists()).toBe(true)
    expect(editWrapper.text()).not.toContain('编辑详情')

    const cancelChange = vi.fn()
    confirmEntryDelete.mockRejectedValueOnce(new Error('cancelled'))
    const cancelWrapper = mount(ResumeEditor, {
      props: { document: makeDocument(), onChange: cancelChange },
    })
    await cancelWrapper.get('.entry-delete-action').trigger('click')
    expect(cancelChange).not.toHaveBeenCalled()

    confirmEntryDelete.mockResolvedValueOnce(true)
    const onChange = vi.fn()
    const deleteWrapper = mount(ResumeEditor, { props: { document: makeDocument(), onChange } })
    await deleteWrapper.get('.entry-delete-action').trigger('click')
    expect(confirmEntryDelete).toHaveBeenCalledWith(
      '其中的工作要点也会一并删除。',
      '删除这段工作经历？',
      expect.objectContaining({ confirmButtonText: '删除', cancelButtonText: '取消' }),
    )
    expect(onChange).toHaveBeenCalledTimes(1)
    expect((onChange.mock.lastCall![0] as ResumeDocument).sections[0]?.entries).toHaveLength(0)
  })

  it('deletes a bullet directly with complete accessible context', async () => {
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document: makeDocument(), onChange } })

    const deleteButton = wrapper.get('.bullet-delete-action')
    expect(deleteButton.text()).toBe('删除')
    expect(deleteButton.attributes('aria-label')).toBe('删除某科技有限公司中的第 1 条工作要点')
    await deleteButton.trigger('click')

    const changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed.sections[0]?.entries[0]?.bullets).toEqual([])
  })

  it('keeps supplemental basics information explicit instead of using an overflow label', async () => {
    const wrapper = mount(ResumeEditor, { props: { document: makeDocument() } })

    expect(wrapper.get('.basics-details summary').text()).toBe('补充信息')
    expect(wrapper.text()).not.toContain('···')
    await wrapper.get('.basics-details summary').trigger('click')
    expect(wrapper.get('.basics-details-menu').text()).toContain('求职意向')
    expect(wrapper.get('.basics-details-menu').text()).toContain('最高学历')
  })

  it('shows duplicate contact values and blank drafts only once', () => {
    const document = makeDocument()
    document.basics.contacts = [
      { id: 'c1', type: 'EMAIL', label: '邮箱', value: 'test@example.com' },
      { id: 'c2', type: 'EMAIL', label: '邮箱', value: 'test@example.com' },
      { id: 'c3', type: 'OTHER', label: '其他', value: '' },
      { id: 'c4', type: 'OTHER', label: '其他', value: '  ' },
    ]

    const wrapper = mount(ResumeEditor, { props: { document } })

    expect(wrapper.findAll('.contact-token')).toHaveLength(2)
  })

  it('renders semantic fields without offering non-exported skill bullets or generic custom metadata', () => {
    const wrapper = mount(ResumeEditor, {
      props: {
        document: {
          schemaVersion: 'RESUME_DOCUMENT_V1',
          basics: { name: '测试用户', contacts: [] },
          sections: [
            {
              id: 'experience',
              kind: 'EXPERIENCE',
              title: '工作经历',
              entries: [
                {
                  id: 'work-1',
                  organization: '某公司',
                  role: '后端工程师',
                  school: null,
                  degree: null,
                  major: null,
                  startDate: '2024.01',
                  endDate: '至今',
                  location: '上海',
                  group: null,
                  skillItems: null,
                  bullets: [{ id: 'work-bullet', text: '交付服务' }],
                },
              ],
            },
            {
              id: 'skills',
              kind: 'SKILL',
              title: '技能',
              entries: [
                {
                  id: 'skill-1',
                  organization: null,
                  role: null,
                  school: null,
                  degree: null,
                  major: null,
                  startDate: null,
                  endDate: null,
                  location: null,
                  group: '后端技术',
                  skillItems: ['Java'],
                  bullets: [{ id: 'hidden-skill-bullet', text: '不应出现在技能编辑器里的内容' }],
                },
              ],
            },
            {
              id: 'custom',
              kind: 'OTHER',
              title: '个人总结',
              entries: [
                {
                  id: 'custom-1',
                  organization: '旧 generic 标题',
                  role: '旧 generic 角色',
                  school: null,
                  degree: null,
                  major: null,
                  startDate: '旧日期',
                  endDate: null,
                  location: null,
                  group: null,
                  skillItems: null,
                  bullets: [{ id: 'custom-bullet', text: '一段总结' }],
                },
              ],
            },
          ],
        },
      },
    })

    expect(wrapper.text()).toContain('上海')
    expect(wrapper.text()).toContain('某公司')
    expect(wrapper.text()).toContain('后端工程师')
    // Generic sections are content-first: compatibility metadata must not be
    // presented as a fake work entry.
    expect(wrapper.get('[data-bullet-id="custom-bullet"] textarea').element.value).toBe('一段总结')
    expect(wrapper.text()).not.toContain('旧 generic 标题')
    expect(wrapper.text()).not.toContain('旧 generic 角色')
    expect(wrapper.text()).not.toContain('旧日期')
    expect(wrapper.text()).not.toContain('经历条目')
    expect(wrapper.text()).not.toContain('添加技能要点')
    expect(wrapper.text()).not.toContain('不应出现在技能编辑器里的内容')
    expect(wrapper.findAll('.editor-entry.is-generic .entry-delete-action.is-inline-delete')).toHaveLength(1)
    expect(wrapper.find('.editor-entry.is-generic .entry-delete-action.is-heading-delete').exists()).toBe(false)
  })

  it('presents generic sections as direct content and preserves bullet identity on edit', async () => {
    const onChange = vi.fn()
    const genericEntry = (entryId: string, bulletId: string, text: string) => ({
      id: entryId,
      organization: '不应作为标题展示',
      role: '不应作为职位展示',
      school: null,
      degree: null,
      major: null,
      startDate: '旧日期',
      endDate: null,
      location: null,
      group: null,
      skillItems: null,
      bullets: [{ id: bulletId, text }],
    })
    const document: ResumeDocument = {
      schemaVersion: 'RESUME_DOCUMENT_V1',
      basics: { name: '测试用户', contacts: [] },
      sections: [
        {
          id: 'summary',
          kind: 'SUMMARY',
          title: '个人总结',
          entries: [genericEntry('summary-entry', 'summary-bullet', '个人总结正文')],
        },
        {
          id: 'certificate',
          kind: 'CERTIFICATE',
          title: '证书',
          entries: [genericEntry('certificate-entry', 'certificate-bullet', 'CET-6')],
        },
        {
          id: 'achievement',
          kind: 'ACHIEVEMENT',
          title: '荣誉奖项',
          entries: [genericEntry('achievement-entry', 'achievement-bullet', '优秀毕业生')],
        },
        {
          id: 'other',
          kind: 'OTHER',
          title: '补充说明',
          entries: [genericEntry('other-entry', 'other-bullet', '可编辑补充内容')],
        },
        {
          id: 'custom',
          kind: 'CUSTOM',
          title: '自定义章节',
          entries: [genericEntry('custom-entry', 'custom-bullet', '自定义内容')],
        },
      ],
    }

    const wrapper = mount(ResumeEditor, { props: { document, onChange } })

    expect(wrapper.get('[data-bullet-id="summary-bullet"] textarea').element.value).toBe(
      '个人总结正文',
    )
    expect(wrapper.get('[data-bullet-id="certificate-bullet"] textarea').element.value).toBe(
      'CET-6',
    )
    expect(wrapper.get('[data-bullet-id="achievement-bullet"] textarea').element.value).toBe(
      '优秀毕业生',
    )
    expect(wrapper.get('[data-bullet-id="custom-bullet"] textarea').element.value).toBe(
      '自定义内容',
    )
    expect(wrapper.text()).not.toContain('经历条目')
    expect(wrapper.text()).not.toContain('不应作为标题展示')
    expect(wrapper.findAll('.entry-document-heading')).toHaveLength(0)
    expect(wrapper.findAll('.entry-delete-action.is-inline-delete')).toHaveLength(5)
    expect(wrapper.find('.entry-delete-action.is-heading-delete').exists()).toBe(false)
    expect(wrapper.findAll('.bullet-block')).toHaveLength(5)

    const input = wrapper.get('[data-bullet-id="summary-bullet"] textarea')
    await input.setValue('更新后的个人总结正文')
    const changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed.sections.map((section) => section.id)).toEqual([
      'summary',
      'certificate',
      'achievement',
      'other',
      'custom',
    ])
    expect(changed.sections[0]?.entries[0]?.id).toBe('summary-entry')
    expect(changed.sections[0]?.entries[0]?.bullets[0]?.id).toBe('summary-bullet')
    expect(changed.sections[0]?.entries[0]?.bullets[0]?.text).toBe('更新后的个人总结正文')
  })

  it('preserves all supported RESUME_DOCUMENT_V1 values while cloning a reactive proxy', async () => {
    const draft = ref<ResumeDocument>({
      schemaVersion: 'RESUME_DOCUMENT_V1',
      basics: {
        name: null,
        contacts: [{ id: 'c1', type: 'EMAIL', label: null, value: 'test@example.com' }],
      },
      sections: [
        {
          id: 's1',
          kind: 'PROJECT',
          title: '项目经历',
          entries: [
            {
              id: 'e1',
              organization: null,
              role: null,
              school: null,
              degree: null,
              major: null,
              startDate: null,
              endDate: null,
              location: null,
              group: null,
              skillItems: null,
              bullets: [{ id: 'b1', text: '原始要点' }],
            },
          ],
        },
      ],
    })
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document: draft.value, onChange } })
    const bulletInput = wrapper
      .findAllComponents({ name: 'ElInput' })
      .find((item) => item.props('type') === 'textarea')

    const updateBullet = bulletInput!.vm.$.vnode.props?.['onUpdate:modelValue'] as
      | ((value: string) => void)
      | undefined
    expect(updateBullet).toBeTypeOf('function')

    updateBullet!('更新后的要点')
    await nextTick()

    const changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed).toEqual({
      schemaVersion: 'RESUME_DOCUMENT_V1',
      basics: {
        name: null,
        contacts: [{ id: 'c1', type: 'EMAIL', label: null, value: 'test@example.com' }],
      },
      sections: [
        {
          id: 's1',
          kind: 'PROJECT',
          title: '项目经历',
          entries: [
            {
              id: 'e1',
              organization: null,
              role: null,
              school: null,
              degree: null,
              major: null,
              startDate: null,
              endDate: null,
              location: null,
              group: null,
              skillItems: null,
              bullets: [{ id: 'b1', text: '更新后的要点' }],
            },
          ],
        },
      ],
    })
  })

  it('keeps long contacts and bullets available without truncating their values', () => {
    const document = makeDocument()
    const longContact = `https://example.com/${'long-profile/'.repeat(12)}`
    const longBullet = `负责中英文混排 delivery、稳定性治理与 URL https://example.com/runbook，${'持续验证真实数据。'.repeat(240)}`
    document.basics.contacts.push({
      id: 'website',
      type: 'WEBSITE',
      label: null,
      value: longContact,
    })
    document.sections[0]!.entries[0]!.bullets[0]!.text = longBullet

    const wrapper = mount(ResumeEditor, { props: { document } })

    expect(wrapper.findAll('.contact-token').map((item) => item.text())).toContain(longContact)
    const bullet = wrapper
      .findAllComponents({ name: 'ElInput' })
      .find((item) => item.props('type') === 'textarea')
    expect(bullet?.props('modelValue')).toBe(longBullet)
  })

  it('focuses a newly added bullet so typing can continue immediately', async () => {
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValueOnce(
      '00000000-0000-4000-8000-000000000001',
    )
    const wrapper = mount(ResumeEditor, {
      props: {
        document: makeDocument(),
        onChange: (document: ResumeDocument) => wrapper.setProps({ document }),
      },
      attachTo: document.body,
    })

    const addBullet = wrapper.findAll('button').find((item) => item.text() === '添加工作要点')
    await addBullet!.trigger('click')
    await nextTick()
    await nextTick()

    const added = wrapper.get('[data-bullet-id="00000000-0000-4000-8000-000000000001"] textarea')
    expect(document.activeElement).toBe(added.element)
    wrapper.unmount()
  })

  it('opens and focuses the first field of a newly added entry', async () => {
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValueOnce(
      '00000000-0000-4000-8000-000000000002',
    )
    const wrapper = mount(ResumeEditor, {
      props: {
        document: makeDocument(),
        onChange: (document: ResumeDocument) => wrapper.setProps({ document }),
      },
      attachTo: document.body,
    })

    const addEntry = wrapper.findAll('button').find((item) => item.text() === '添加工作经历')
    await addEntry!.trigger('click')
    await nextTick()
    await nextTick()

    const entry = wrapper.get('[data-entry-id="00000000-0000-4000-8000-000000000002"]')
    expect(entry.find('.entry-inline-editor').exists()).toBe(true)
    expect(entry.element.contains(document.activeElement)).toBe(true)
    wrapper.unmount()
  })

  it('preserves manual section context while navigating between requirements', async () => {
    const document = makeDocument()
    document.sections.push(
      {
        id: 's2',
        kind: 'PROJECT',
        title: '项目经历',
        entries: [],
      },
      {
        id: 's3',
        kind: 'OTHER',
        title: '补充内容',
        entries: [],
      },
    )
    const wrapper = mount(ResumeEditor, { props: { document } })
    await nextTick()

    for (const sectionId of ['s1', 's2', 's3']) {
      expect(
        wrapper
          .get(`[data-section-id="${sectionId}"] .section-collapse-toggle`)
          .attributes('aria-expanded'),
      ).toBe('true')
    }

    await wrapper.get('[data-section-id="s1"] .section-collapse-toggle').trigger('click')
    await wrapper.get('[data-section-id="s2"] .section-collapse-toggle').trigger('click')
    await wrapper.setProps({ selectedSectionId: 's3' })
    await nextTick()

    expect(
      wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('false')
    expect(
      wrapper.get('[data-section-id="s2"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('false')
    expect(
      wrapper.get('[data-section-id="s3"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('true')

    await wrapper.setProps({ selectedSectionId: 's1', focusedBulletId: 'b1' })
    await nextTick()
    expect(
      wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('true')
    expect(wrapper.get('[data-bullet-id="b1"]').classes()).toContain('is-evidence-focus')
    expect(
      wrapper.get('[data-section-id="s2"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('false')

    const updatedDocument = JSON.parse(JSON.stringify(document)) as ResumeDocument
    updatedDocument.sections[0]!.entries[0]!.bullets[0]!.text = '后台自动保存后的内容'
    await wrapper.setProps({ document: updatedDocument })
    await nextTick()
    expect(
      wrapper.get('[data-section-id="s2"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('false')
  })

  it('opens new sections once and preserves expansion state across reorder', async () => {
    const document = makeDocument()
    document.sections.push({ id: 's2', kind: 'PROJECT', title: '项目经历', entries: [] })
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document, onChange } })
    await nextTick()

    await wrapper.get('[data-section-id="s1"] .section-collapse-toggle').trigger('click')
    const updatedDocument = JSON.parse(JSON.stringify(document)) as ResumeDocument
    updatedDocument.sections.push({ id: 's3', kind: 'OTHER', title: '补充内容', entries: [] })
    await wrapper.setProps({ document: updatedDocument })
    await nextTick()
    expect(
      wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('false')
    expect(
      wrapper.get('[data-section-id="s3"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('true')

    await wrapper
      .get('[data-section-id="s1"]')
      .trigger('keydown', { key: 'ArrowDown', altKey: true })
    const reordered = onChange.mock.lastCall![0] as ResumeDocument
    expect(reordered.sections.map((section) => section.id)).toEqual(['s2', 's1', 's3'])
    await wrapper.setProps({ document: reordered })
    await nextTick()
    expect(
      wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('false')
    expect(
      wrapper.get('[data-section-id="s3"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('true')

    const withoutS1 = JSON.parse(JSON.stringify(reordered)) as ResumeDocument
    withoutS1.sections = withoutS1.sections.filter((section) => section.id !== 's1')
    await wrapper.setProps({ document: withoutS1 })
    await nextTick()
    expect(wrapper.find('[data-section-id="s1"]').exists()).toBe(false)

    const restored = JSON.parse(JSON.stringify(withoutS1)) as ResumeDocument
    restored.sections.push(JSON.parse(JSON.stringify(document.sections[0])))
    await wrapper.setProps({ document: restored })
    await nextTick()
    expect(
      wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded'),
    ).toBe('true')
  })

  it('reorders a section from its explicit handle without a hold delay', async () => {
    const document = makeDocument()
    document.sections.push({ id: 's2', kind: 'PROJECT', title: '项目经历', entries: [] })
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document, onChange } })
    const firstSection = wrapper.get('[data-section-id="s1"]')
    const secondSection = wrapper.get('[data-section-id="s2"]')
    const originalRect = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function () {
      if (this.getAttribute('data-section-id') === 's1') {
        return { top: 0, bottom: 100, height: 100, left: 0, right: 500, width: 500, x: 0, y: 0, toJSON: () => ({}) }
      }
      if (this.getAttribute('data-section-id') === 's2') {
        return { top: 120, bottom: 220, height: 100, left: 0, right: 500, width: 500, x: 0, y: 120, toJSON: () => ({}) }
      }
      return originalRect.call(this)
    })

    expect(wrapper.find('.section-drag-handle').exists()).toBe(true)
    await firstSection.get('.section-title-display').trigger('click')
    expect(wrapper.find('.section-title-input').exists()).toBe(true)
    expect(onChange).not.toHaveBeenCalled()

    const dispatchPointer = (type: string, target: EventTarget, values: Record<string, unknown>) => {
      const event = new Event(type, { bubbles: true, cancelable: true })
      Object.defineProperties(event, values)
      target.dispatchEvent(event)
    }
    const handle = firstSection.get('.section-drag-handle')
    dispatchPointer('pointerdown', handle.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 1 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    expect(firstSection.classes()).not.toContain('is-reorder-source')
    const move = new Event('pointermove', { bubbles: true, cancelable: true })
    Object.defineProperties(move, {
      pointerId: { value: 1 }, clientX: { value: 20 }, clientY: { value: 210 },
    })
    window.dispatchEvent(move)
    await nextTick()
    expect(firstSection.classes()).toContain('is-reorder-source')
    expect(secondSection.classes()).toContain('is-drop-after')
    dispatchPointer('pointerup', window, {
      pointerId: { value: 1 }, clientX: { value: 20 }, clientY: { value: 210 },
    })
    await nextTick()

    expect(onChange).toHaveBeenCalledTimes(1)
    const changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed.sections.map((section) => section.id)).toEqual(['s2', 's1'])
    expect(changed.sections[1]?.entries[0]?.id).toBe('e1')
    expect(changed.sections[1]?.entries[0]?.bullets[0]?.id).toBe('b1')
    vi.restoreAllMocks()
    wrapper.unmount()
  })

  it('reorders entries within one section and keeps every nested id', async () => {
    const entry = (id: string, bulletId: string) => ({
      id,
      organization: id,
      role: '工程师',
      school: null,
      degree: null,
      major: null,
      startDate: null,
      endDate: null,
      location: null,
      group: null,
      skillItems: null,
      bullets: [{ id: bulletId, text: `${id} bullet` }],
    })
    const document: ResumeDocument = {
      ...makeDocument(),
      sections: [{ ...makeDocument().sections[0]!, entries: [entry('a', 'ba'), entry('b', 'bb'), entry('c', 'bc')] }],
    }
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document, onChange } })
    const originalRect = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function () {
      const sectionId = this.getAttribute('data-section-id')
      const entryId = this.getAttribute('data-entry-id')
      if (sectionId === 's1') return { top: 0, bottom: 300, height: 300, left: 0, right: 500, width: 500, x: 0, y: 0, toJSON: () => ({}) }
      const tops: Record<string, number> = { a: 10, b: 80, c: 150 }
      if (entryId && tops[entryId] !== undefined) {
        const top = tops[entryId]
        return { top, bottom: top + 50, height: 50, left: 0, right: 500, width: 500, x: 0, y: top, toJSON: () => ({}) }
      }
      return originalRect.call(this)
    })
    const dispatch = (type: string, target: EventTarget, pointerId: number, clientY: number) => {
      const event = new Event(type, { bubbles: true, cancelable: true })
      Object.defineProperties(event, { pointerId: { value: pointerId }, button: { value: 0 }, clientX: { value: 10 }, clientY: { value: clientY } })
      target.dispatchEvent(event)
    }
    const drag = async (sourceId: string, targetY: number, pointerId: number) => {
      const handle = wrapper.get(`[data-entry-id="${sourceId}"] .entry-drag-handle`)
      dispatch('pointerdown', handle.element, pointerId, 30)
      const move = new Event('pointermove', { bubbles: true, cancelable: true })
      Object.defineProperties(move, { pointerId: { value: pointerId }, clientX: { value: 10 }, clientY: { value: targetY } })
      window.dispatchEvent(move)
      await nextTick()
      dispatch('pointerup', window, pointerId, targetY)
      await nextTick()
    }

    await drag('b', 15, 1)
    let changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed.sections[0]?.entries.map((item) => item.id)).toEqual(['b', 'a', 'c'])
    expect(changed.sections[0]?.entries[0]?.bullets[0]?.id).toBe('bb')
    await wrapper.setProps({ document: changed })
    await drag('a', 180, 2)
    changed = onChange.mock.lastCall![0] as ResumeDocument
    expect(changed.sections[0]?.entries.map((item) => item.id)).toEqual(['b', 'c', 'a'])
    expect(changed.sections[0]?.entries[2]?.bullets[0]?.id).toBe('ba')
    await wrapper.setProps({ document: changed })
    await wrapper.get('[data-entry-id="b"] .entry-drag-handle').trigger('keydown', {
      key: 'ArrowDown',
      altKey: true,
    })
    const keyboardChanged = onChange.mock.lastCall![0] as ResumeDocument
    expect(keyboardChanged.sections[0]?.entries.map((item) => item.id)).toEqual(['c', 'b', 'a'])
    expect(onChange).toHaveBeenCalledTimes(3)
    vi.restoreAllMocks()
    wrapper.unmount()
  })

  it('moves entries once across compatible sections, rejects incompatible sections, and accepts empty targets', async () => {
    const makeEntry = (id: string, bulletId: string) => ({
      id, organization: id, role: null, school: null, degree: null, major: null,
      startDate: null, endDate: null, location: null, group: null, skillItems: null,
      bullets: [{ id: bulletId, text: id }],
    })
    const makeCrossDocument = (targetKind: string, targetEntries: ResumeDocument['sections'][number]['entries']) => ({
      schemaVersion: 'RESUME_DOCUMENT_V1',
      basics: { name: '测试', contacts: [] },
      sections: [
        { id: 'source', kind: 'EXPERIENCE', title: '正式工作经历', entries: [makeEntry('entry-a', 'bullet-a')] },
        { id: 'target', kind: targetKind, title: targetKind === 'EDUCATION' ? '教育经历' : '实习经历', entries: targetEntries },
      ],
    }) as ResumeDocument
    const originalRect = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function () {
      const sectionId = this.getAttribute('data-section-id')
      const entryId = this.getAttribute('data-entry-id')
      if (sectionId === 'source') return { top: 0, bottom: 100, height: 100, left: 0, right: 500, width: 500, x: 0, y: 0, toJSON: () => ({}) }
      if (sectionId === 'target') return { top: 120, bottom: 300, height: 180, left: 0, right: 500, width: 500, x: 0, y: 120, toJSON: () => ({}) }
      if (entryId === 'entry-b') return { top: 140, bottom: 200, height: 60, left: 0, right: 500, width: 500, x: 0, y: 140, toJSON: () => ({}) }
      return originalRect.call(this)
    })
    const dispatch = (type: string, target: EventTarget, pointerId: number, clientY: number) => {
      const event = new Event(type, { bubbles: true, cancelable: true })
      Object.defineProperties(event, { pointerId: { value: pointerId }, button: { value: 0 }, clientX: { value: 10 }, clientY: { value: clientY } })
      target.dispatchEvent(event)
    }
    const runDrag = async (wrapper: ReturnType<typeof mount>, pointerId: number) => {
      const handle = wrapper.get('[data-entry-id="entry-a"] .entry-drag-handle')
      dispatch('pointerdown', handle.element, pointerId, 30)
      const move = new Event('pointermove', { bubbles: true, cancelable: true })
      Object.defineProperties(move, { pointerId: { value: pointerId }, clientX: { value: 10 }, clientY: { value: 170 } })
      window.dispatchEvent(move)
      await nextTick()
      dispatch('pointerup', window, pointerId, 170)
      await nextTick()
    }

    const compatible = mount(ResumeEditor, {
      props: { document: makeCrossDocument('EXPERIENCE', [makeEntry('entry-b', 'bullet-b')]) },
    })
    const compatibleChange = vi.fn()
    await compatible.setProps({ onChange: compatibleChange })
    await runDrag(compatible, 1)
    expect(compatibleChange).toHaveBeenCalledTimes(1)
    const moved = compatibleChange.mock.lastCall![0] as ResumeDocument
    expect(moved.sections[0]?.entries).toEqual([])
    expect(moved.sections[1]?.entries.map((item) => item.id)).toEqual(['entry-b', 'entry-a'])
    expect(moved.sections[1]?.entries[1]?.bullets[0]?.id).toBe('bullet-a')
    compatible.unmount()

    const incompatibleChange = vi.fn()
    const incompatible = mount(ResumeEditor, {
      props: { document: makeCrossDocument('EDUCATION', [makeEntry('entry-b', 'bullet-b')]), onChange: incompatibleChange },
    })
    await runDrag(incompatible, 2)
    expect(incompatibleChange).not.toHaveBeenCalled()
    expect(incompatible.find('.editor-section.is-entry-drop-end').exists()).toBe(false)
    incompatible.unmount()

    const emptyChange = vi.fn()
    const empty = mount(ResumeEditor, {
      props: { document: makeCrossDocument('EXPERIENCE', []), onChange: emptyChange },
    })
    await runDrag(empty, 3)
    expect(emptyChange).toHaveBeenCalledTimes(1)
    const emptyMoved = emptyChange.mock.lastCall![0] as ResumeDocument
    expect(emptyMoved.sections[0]?.entries).toHaveLength(0)
    expect(emptyMoved.sections[1]?.entries[0]?.id).toBe('entry-a')
    empty.unmount()
    vi.restoreAllMocks()
  })

  it('does not start section reorder when a bullet delete action receives pointerdown', async () => {
    vi.useFakeTimers()
    const document = makeDocument()
    document.sections.push({ id: 's2', kind: 'PROJECT', title: '项目经历', entries: [] })
    const wrapper = mount(ResumeEditor, { props: { document } })

    await wrapper
      .get('.bullet-delete-action')
      .trigger('pointerdown', { pointerId: 1, pointerType: 'mouse', button: 0 })
    await vi.advanceTimersByTimeAsync(230)
    expect(wrapper.get('[data-section-id="s1"]').classes()).not.toContain('is-reorder-source')

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('supports keyboard section reorder alongside the visible drag handle', async () => {
    const document = makeDocument()
    document.sections.push({ id: 's2', kind: 'PROJECT', title: '项目经历', entries: [] })
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document, onChange } })
    const firstSection = wrapper.get('[data-section-id="s1"]')

    expect(firstSection.attributes('tabindex')).toBe('0')
    await firstSection.trigger('keydown', { key: 'ArrowDown', altKey: true })

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(
      (onChange.mock.lastCall![0] as ResumeDocument).sections.map((section) => section.id),
    ).toEqual(['s2', 's1'])
    expect(wrapper.find('.section-drag-handle').exists()).toBe(true)
    expect(wrapper.text()).toContain('拖动章节标题旁的排序按钮')
  })

  it('exits contextual name editing with Escape', async () => {
    const wrapper = mount(ResumeEditor, { props: { document: makeDocument() } })
    await wrapper.get('.identity-name').trigger('click')
    await wrapper.get('.identity-name-input').trigger('keyup', { key: 'Escape' })
    expect(wrapper.find('.identity-name-input').exists()).toBe(false)
    expect(wrapper.find('.identity-name').exists()).toBe(true)
  })

  it('keeps section and entry editing exits predictable from the keyboard', async () => {
    const wrapper = mount(ResumeEditor, { props: { document: makeDocument() } })

    await wrapper.get('.section-title-display').trigger('click')
    await wrapper.get('.section-title-input').trigger('keyup', { key: 'Escape' })
    expect(wrapper.find('.section-title-display').exists()).toBe(true)

    await wrapper.get('.entry-title-display').trigger('click')
    expect(wrapper.find('.entry-inline-editor').exists()).toBe(true)
    await wrapper.get('.entry-inline-editor').trigger('keydown', { key: 'Enter' })
    expect(wrapper.find('.entry-title-display').exists()).toBe(true)
  })
})
