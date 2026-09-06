// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import ResumeEditor from '@/components/workspace/ResumeEditor.vue'
import type { ResumeDocument } from '@/types/resume-document'

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
    template: '<button :disabled="disabled"><slot /></button>',
  },
  ElDropdown: { template: '<div><slot /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { template: '<div><slot /></div>' },
}))

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn() },
  ...elementPlusStubs,
}))

// unplugin-vue-components 将模板组件从 element-plus/es 局部导入；两处均替换为稳定测试 stub。
vi.mock('element-plus/es', () => elementPlusStubs)

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
    expect(wrapper.text()).toContain('旧 generic 标题')
    expect(wrapper.text()).toContain('旧 generic 角色')
    expect(wrapper.text()).toContain('旧日期')
    expect(wrapper.text()).not.toContain('添加技能要点')
    expect(wrapper.text()).not.toContain('不应出现在技能编辑器里的内容')
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
    document.basics.contacts.push({ id: 'website', type: 'WEBSITE', label: null, value: longContact })
    document.sections[0]!.entries[0]!.bullets[0]!.text = longBullet

    const wrapper = mount(ResumeEditor, { props: { document } })

    expect(wrapper.findAll('.contact-token').map((item) => item.text())).toContain(longContact)
    const bullet = wrapper
      .findAllComponents({ name: 'ElInput' })
      .find((item) => item.props('type') === 'textarea')
    expect(bullet?.props('modelValue')).toBe(longBullet)
  })

  it('focuses a newly added bullet so typing can continue immediately', async () => {
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValueOnce('00000000-0000-4000-8000-000000000001')
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
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValueOnce('00000000-0000-4000-8000-000000000002')
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
      expect(wrapper.get(`[data-section-id="${sectionId}"] .section-collapse-toggle`).attributes('aria-expanded')).toBe('true')
    }

    await wrapper.get('[data-section-id="s1"] .section-collapse-toggle').trigger('click')
    await wrapper.get('[data-section-id="s2"] .section-collapse-toggle').trigger('click')
    await wrapper.setProps({ selectedSectionId: 's3' })
    await nextTick()

    expect(wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[data-section-id="s2"] .section-collapse-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[data-section-id="s3"] .section-collapse-toggle').attributes('aria-expanded')).toBe('true')

    await wrapper.setProps({ selectedSectionId: 's1', focusedBulletId: 'b1' })
    await nextTick()
    expect(wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-bullet-id="b1"]').classes()).toContain('is-evidence-focus')
    expect(wrapper.get('[data-section-id="s2"] .section-collapse-toggle').attributes('aria-expanded')).toBe('false')

    const updatedDocument = JSON.parse(JSON.stringify(document)) as ResumeDocument
    updatedDocument.sections[0]!.entries[0]!.bullets[0]!.text = '后台自动保存后的内容'
    await wrapper.setProps({ document: updatedDocument })
    await nextTick()
    expect(wrapper.get('[data-section-id="s2"] .section-collapse-toggle').attributes('aria-expanded')).toBe('false')
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
    expect(wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[data-section-id="s3"] .section-collapse-toggle').attributes('aria-expanded')).toBe('true')

    await wrapper.get('[data-section-id="s1"]').trigger('keydown', { key: 'ArrowDown', altKey: true })
    const reordered = onChange.mock.lastCall![0] as ResumeDocument
    expect(reordered.sections.map((section) => section.id)).toEqual(['s2', 's1', 's3'])
    await wrapper.setProps({ document: reordered })
    await nextTick()
    expect(wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[data-section-id="s3"] .section-collapse-toggle').attributes('aria-expanded')).toBe('true')

    const withoutS1 = JSON.parse(JSON.stringify(reordered)) as ResumeDocument
    withoutS1.sections = withoutS1.sections.filter((section) => section.id !== 's1')
    await wrapper.setProps({ document: withoutS1 })
    await nextTick()
    expect(wrapper.find('[data-section-id="s1"]').exists()).toBe(false)

    const restored = JSON.parse(JSON.stringify(withoutS1)) as ResumeDocument
    restored.sections.push(JSON.parse(JSON.stringify(document.sections[0])))
    await wrapper.setProps({ document: restored })
    await nextTick()
    expect(wrapper.get('[data-section-id="s1"] .section-collapse-toggle').attributes('aria-expanded')).toBe('true')
  })

  it('reorders a section only after a desktop hold-drag and emits one document update', async () => {
    vi.useFakeTimers()
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

    expect(wrapper.find('.section-drag-handle').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('上移')
    expect(wrapper.text()).not.toContain('下移')
    const dispatchPointer = (type: string, target: EventTarget, values: Record<string, unknown>) => {
      const event = new Event(type, { bubbles: true, cancelable: true })
      Object.defineProperties(event, values)
      target.dispatchEvent(event)
    }
    dispatchPointer('pointerdown', firstSection.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 1 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    dispatchPointer('pointerup', firstSection.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 1 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    expect(onChange).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)

    const textarea = firstSection.get('textarea')
    dispatchPointer('pointerdown', textarea.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 2 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    await vi.advanceTimersByTimeAsync(230)
    await nextTick()
    expect(firstSection.classes()).not.toContain('is-reorder-source')
    dispatchPointer('pointerup', textarea.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 2 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })

    dispatchPointer('pointerdown', firstSection.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 3 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    await vi.advanceTimersByTimeAsync(230)
    await nextTick()
    dispatchPointer('pointerup', firstSection.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 3 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    expect(onChange).not.toHaveBeenCalled()

    dispatchPointer('pointerdown', firstSection.element, {
      button: { value: 0 }, pointerType: { value: 'mouse' }, pointerId: { value: 1 },
      clientX: { value: 12 }, clientY: { value: 20 },
    })
    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(230)
    await nextTick()
    expect(firstSection.classes()).toContain('is-reorder-source')
    const move = new Event('pointermove', { bubbles: true, cancelable: true })
    Object.defineProperties(move, { clientX: { value: 20 }, clientY: { value: 210 } })
    window.dispatchEvent(move)
    await nextTick()
    expect(secondSection.classes()).toContain('is-drop-after')
    const drop = new Event('pointerup', { bubbles: true, cancelable: true })
    Object.defineProperties(drop, { clientX: { value: 20 }, clientY: { value: 210 } })
    window.dispatchEvent(drop)
    await nextTick()

    expect(onChange).toHaveBeenCalledTimes(1)
    expect((onChange.mock.lastCall![0] as ResumeDocument).sections.map((section) => section.id)).toEqual(['s2', 's1'])
    expect((onChange.mock.lastCall![0] as ResumeDocument).sections[1]?.entries[0]?.bullets[0]?.id).toBe('b1')
    wrapper.unmount()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('supports keyboard section reorder without exposing a sorting control', async () => {
    const document = makeDocument()
    document.sections.push({ id: 's2', kind: 'PROJECT', title: '项目经历', entries: [] })
    const onChange = vi.fn()
    const wrapper = mount(ResumeEditor, { props: { document, onChange } })
    const firstSection = wrapper.get('[data-section-id="s1"]')

    expect(firstSection.attributes('tabindex')).toBe('0')
    await firstSection.trigger('keydown', { key: 'ArrowDown', altKey: true })

    expect(onChange).toHaveBeenCalledTimes(1)
    expect((onChange.mock.lastCall![0] as ResumeDocument).sections.map((section) => section.id)).toEqual(['s2', 's1'])
    expect(wrapper.text()).not.toContain('拖动排序')
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
