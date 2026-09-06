// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkspacePreviewExport from '@/components/workspace/WorkspacePreviewExport.vue'
import {
  deleteWorkspaceArtifact,
  downloadArtifactPdf,
  exportWorkspacePdf,
  listWorkspaceArtifacts,
  previewWorkspacePdf,
} from '@/api/workspace'

const { messageError, messageWarning, messageSuccess } = vi.hoisted(() => ({
  messageError: vi.fn(),
  messageWarning: vi.fn(),
  messageSuccess: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: messageError,
    warning: messageWarning,
    success: messageSuccess,
  },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) },
  ElButton: {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled"><slot /></button>',
  },
  ElRadioGroup: {
    name: 'ElRadioGroup',
    props: ['modelValue', 'size'],
    emits: ['update:modelValue'],
    template: '<div><slot /></div>',
  },
  ElRadioButton: {
    props: ['value'],
    template: '<span><slot /></span>',
  },
}))

vi.mock('@/api/workspace', () => ({
  previewWorkspacePdf: vi.fn(),
  exportWorkspacePdf: vi.fn(),
  listWorkspaceArtifacts: vi.fn(),
  downloadArtifactPdf: vi.fn(),
  deleteWorkspaceArtifact: vi.fn(),
}))

const previewMock = vi.mocked(previewWorkspacePdf)
const exportMock = vi.mocked(exportWorkspacePdf)
const listMock = vi.mocked(listWorkspaceArtifacts)
const downloadMock = vi.mocked(downloadArtifactPdf)
const deleteMock = vi.mocked(deleteWorkspaceArtifact)

const staleHandler = vi.fn()
const pdfBlob = new Blob(['%PDF-1.7 component'], { type: 'application/pdf' })

const previewResult = (revision = 3) => ({
  blob: pdfBlob,
  contentRevision: revision,
  targetResumeVersionId: 99,
  templateVersion: '1',
  rendererVersion: 'typst-resume-renderer/1',
  previewReceipt: 'signed-receipt',
  preflight: {
    pageCount: 2,
    missingContact: false,
    pageLimitExceeded: false,
    overflowDetected: false,
    orphanFinalPage: false,
    readabilityTooSmall: false,
    needsReview: false,
  },
})

const artifact = {
  id: 11,
  optimizationTaskId: 42,
  templateId: 'classic' as const,
  templateVersion: '1',
  rendererVersion: 'typst-resume-renderer/1',
  contentRevision: 3,
  mimeType: 'application/pdf',
  fileSize: 1234,
  checksum: 'a'.repeat(64),
  status: 'READY' as const,
  pageCount: 2,
  missingContact: false,
  pageLimitExceeded: false,
  overflowDetected: false,
  orphanFinalPage: false,
  readabilityTooSmall: false,
  documentGateStatus: 'PASS',
  fileName: 'resume.pdf',
  createdAt: '2026-08-21T00:00:00',
}

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

const mountComponent = (
  status: 'dirty' | 'saving' | 'saved' | 'failed' | 'conflict' = 'saved',
  revision = 3,
) =>
  mount(WorkspacePreviewExport, {
    props: {
      optimizationTaskId: 42,
      revision,
      status,
      onStale: staleHandler,
    },
  })

const button = (wrapper: ReturnType<typeof mountComponent>, text: string) =>
  wrapper.findAll('button').find((item) => item.text().includes(text))!

const openHistory = async (wrapper: ReturnType<typeof mountComponent>) => {
  const history = wrapper.find('details.export-history')
  ;(history.element as HTMLDetailsElement).open = true
  await history.trigger('toggle')
  await flushPromises()
}

describe('WorkspacePreviewExport', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    staleHandler.mockClear()
    previewMock.mockResolvedValue(previewResult())
    listMock.mockResolvedValue([])
    downloadMock.mockResolvedValue(pdfBlob)
    deleteMock.mockResolvedValue(undefined)
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:preview'),
      revokeObjectURL: vi.fn(),
    })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  })

  it('does not render revision zero before the first CAS save', async () => {
    const wrapper = mountComponent('saved', 0)

    await flushPromises()

    expect(previewMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先完成一次保存，再生成预览')
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
  })

  it('auto-generates a valid preview and only then enables export with its signed receipt', async () => {
    exportMock.mockResolvedValue(artifact)
    const wrapper = mountComponent()

    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
    await flushPromises()

    expect(wrapper.text()).toContain('可以导出')
    expect(wrapper.text()).toContain('2 页')
    expect(wrapper.find('.preview-document-toolbar .preview-open-link').text()).toContain('在新窗口打开 PDF')
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeUndefined()
    await button(wrapper, '导出 PDF').trigger('click')
    await flushPromises()

    expect(exportMock).toHaveBeenCalledWith(42, {
      templateId: 'classic',
      expectedRevision: 3,
      previewReceipt: 'signed-receipt',
    })
  })

  it('reports a post-export download failure separately and offers the artifact download retry', async () => {
    exportMock.mockResolvedValue(artifact)
    downloadMock.mockRejectedValue(new Error('导出文件暂时无法下载'))
    const wrapper = mountComponent()
    await flushPromises()

    await button(wrapper, '导出 PDF').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('PDF 下载失败')
    expect(wrapper.text()).toContain('导出文件暂时无法下载')
    expect(button(wrapper, '重新下载')).toBeTruthy()
  })

  it('shows contact, page-limit, and executable overflow warnings from real preflight', async () => {
    previewMock.mockResolvedValue({
      ...previewResult(),
      preflight: {
        pageCount: 3,
        missingContact: true,
        pageLimitExceeded: true,
        overflowDetected: true,
        orphanFinalPage: false,
        readabilityTooSmall: false,
        needsReview: false,
      },
    })
    const wrapper = mountComponent()
    await flushPromises()

    expect(wrapper.text()).toContain('3 页')
    expect(wrapper.text()).toContain('缺少可用联系方式')
    expect(wrapper.text()).toContain('超过建议的 2 页')
    expect(wrapper.text()).toContain('文字超出页面边界')
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
  })

  it('drops a late preview response after revision changes', async () => {
    const pending = deferred<ReturnType<typeof previewResult>>()
    previewMock.mockReturnValue(pending.promise)
    const wrapper = mountComponent()
    await wrapper.setProps({ revision: 4 })
    pending.resolve(previewResult(3))
    await flushPromises()

    expect(wrapper.text()).not.toContain('可以导出')
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
  })

  it('invalidates preview on template, task, or save-status changes', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeUndefined()

    wrapper.findComponent({ name: 'ElRadioGroup' }).vm.$emit('update:modelValue', 'modern')
    await flushPromises()
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ optimizationTaskId: 43 })
    await wrapper.setProps({ status: 'conflict' })
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
  })

  it('emits stale and does not retain preview when server returns 409', async () => {
    const stale = Object.assign(new Error('预览已失效'), { code: 409 })
    previewMock.mockRejectedValue(stale)
    const wrapper = mountComponent()
    await flushPromises()

    expect(staleHandler).toHaveBeenCalledTimes(1)
    expect(messageWarning).toHaveBeenCalledWith('预览已失效')
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
  })

  it('drops a stale preview failure after a newer local edit instead of requesting server adoption', async () => {
    const pending = deferred<ReturnType<typeof previewResult>>()
    previewMock.mockReturnValue(pending.promise)
    const wrapper = mountComponent()
    await wrapper.setProps({ status: 'dirty' })
    pending.reject(Object.assign(new Error('预览已失效'), { code: 409 }))
    await flushPromises()

    expect(staleHandler).not.toHaveBeenCalled()
    expect(messageWarning).not.toHaveBeenCalled()
  })

  it('drops a stale export failure after a newer local edit instead of requesting server adoption', async () => {
    const pending = deferred<typeof artifact>()
    exportMock.mockReturnValue(pending.promise)
    const wrapper = mountComponent()
    await flushPromises()

    await button(wrapper, '导出 PDF').trigger('click')
    await wrapper.setProps({ status: 'dirty' })
    pending.reject(Object.assign(new Error('预览凭证已过期'), { code: 409 }))
    await flushPromises()

    expect(staleHandler).not.toHaveBeenCalled()
    expect(messageWarning).not.toHaveBeenCalled()
  })

  it('does not auto-download an export that completes after a newer local edit', async () => {
    const pending = deferred<typeof artifact>()
    exportMock.mockReturnValue(pending.promise)
    const wrapper = mountComponent()
    await flushPromises()

    await button(wrapper, '导出 PDF').trigger('click')
    await wrapper.setProps({ status: 'dirty' })
    pending.resolve(artifact)
    await flushPromises()

    expect(downloadMock).not.toHaveBeenCalled()
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled()
  })

  it('renders an artifact-list load failure inside the collapsed export history', async () => {
    listMock.mockRejectedValue(new Error('读取导出记录失败'))
    const wrapper = mountComponent()
    await flushPromises()
    await openHistory(wrapper)

    expect(wrapper.text()).toContain('导出记录加载失败')
    expect(wrapper.text()).toContain('读取导出记录失败')
    expect(wrapper.text()).not.toContain('还没有导出记录。')
  })

  it('surfaces non-stale preview errors without enabling export', async () => {
    previewMock.mockRejectedValue(new Error('PDF response invalid'))
    const wrapper = mountComponent()
    await flushPromises()

    expect(messageError).toHaveBeenCalledWith('PDF response invalid')
    expect(wrapper.text()).toContain('PDF 预览未生成')
    expect(wrapper.text()).toContain('PDF response invalid')
    expect(button(wrapper, '导出 PDF').attributes('disabled')).toBeDefined()
  })
})
