import { describe, expect, it } from 'vitest'
import type { ResumeListItem } from '@/types/resume'
import {
  getResumeFileValidationError,
  getResumeStatus,
  getStartBlockReason,
  pickInitialResumeId,
} from '../homeComposer'

const resume = (overrides: Partial<ResumeListItem> = {}): ResumeListItem => ({
  id: 1,
  originalFilename: 'resume.pdf',
  fileType: 'PDF',
  fileSize: 1024,
  uploadStatus: 'SUCCESS',
  parseStatus: 'SUCCESS',
  qualityStatus: 'READY',
  canonicalReady: true,
  parseErrorMessage: null,
  createdAt: '2026-01-01T00:00:00Z',
  ...overrides,
})

const file = (name: string, size: number) => ({ name, size }) as File

describe('homeComposer', () => {
  it('defaults to the first ready resume instead of an unavailable one', () => {
    expect(
      pickInitialResumeId([
        resume({ id: 1, qualityStatus: 'NEEDS_REVIEW' }),
        resume({ id: 2 }),
      ], null),
    ).toBe(2)
  })

  it('keeps the current selected resume when the list is refreshed', () => {
    expect(pickInitialResumeId([resume({ id: 2 })], 2)).toBe(2)
  })

  it('honors an uploaded preferred resume', () => {
    expect(pickInitialResumeId([resume({ id: 1 }), resume({ id: 2 })], 1, 2)).toBe(2)
  })

  it('returns no selection for an empty resume list', () => {
    expect(pickInitialResumeId([], null)).toBeNull()
  })

  it('describes an empty resume selection', () => {
    expect(getResumeStatus(null).kind).toBe('empty')
  })

  it('describes a ready resume as usable for analysis', () => {
    expect(getResumeStatus(resume())).toMatchObject({ kind: 'ready', label: '可用于分析' })
  })

  it('describes a resume preparation task with its live message', () => {
    expect(getResumeStatus(resume(), 42, '正在抽取文本')).toMatchObject({
      kind: 'preparing',
      label: '正在抽取文本',
    })
  })

  it('describes a pending resume without a task as preparing', () => {
    expect(getResumeStatus(resume({ qualityStatus: 'PENDING' })).kind).toBe('preparing')
  })

  it('describes a resume that needs user confirmation', () => {
    expect(getResumeStatus(resume({ qualityStatus: 'NEEDS_REVIEW' })).kind).toBe('needs-review')
  })

  it('describes a stale canonical version as requiring reparse', () => {
    expect(getResumeStatus(resume({ canonicalReady: false })).kind).toBe('reparse')
  })

  it('describes parse failure without relying on a color alone', () => {
    expect(getResumeStatus(resume({ parseStatus: 'FAILED', parseErrorMessage: '文件损坏' }))).toMatchObject({
      kind: 'failed',
      description: '文件损坏',
    })
  })

  it('blocks when the JD is empty', () => {
    expect(getStartBlockReason({ resume: resume(), jobDescription: '  ' })).toBe('请粘贴目标岗位 JD')
  })

  it('blocks while the selected resume is preparing', () => {
    expect(getStartBlockReason({
      resume: resume({ qualityStatus: 'PENDING' }),
      jobDescription: '岗位要求',
    })).toBe('当前简历仍在准备')
  })

  it('blocks a resume that needs confirmation', () => {
    expect(getStartBlockReason({
      resume: resume({ qualityStatus: 'NEEDS_REVIEW' }),
      jobDescription: '岗位要求',
    })).toBe('当前简历需要确认')
  })

  it('blocks a ready resume until BYOK is active', () => {
    expect(getStartBlockReason({
      resume: resume(),
      jobDescription: '岗位要求',
      aiConfigurationState: 'UNCONFIGURED',
    })).toBe('AI 尚未配置')
    expect(getStartBlockReason({
      resume: resume(),
      jobDescription: '岗位要求',
      aiConfigurationState: 'SAVED_DISABLED',
    })).toBe('AI 配置尚未启用')
  })

  it('allows a ready resume and non-empty JD with active BYOK', () => {
    expect(getStartBlockReason({
      resume: resume(),
      jobDescription: '岗位要求',
      aiConfigurationState: 'ACTIVE',
    })).toBe('')
  })

  it('blocks duplicate submission while analysis is running', () => {
    expect(getStartBlockReason({
      resume: resume(),
      jobDescription: '岗位要求',
      analysisRunning: true,
    })).toBe('岗位分析正在进行')
  })

  it('reports the startup phase before the analysis task exists', () => {
    expect(getStartBlockReason({
      resume: resume(),
      jobDescription: '岗位要求',
      startingAnalysis: true,
    })).toBe('当前任务正在启动')
  })

  it('accepts PDF, DOC and DOCX files', () => {
    expect(getResumeFileValidationError(file('resume.PDF', 1024))).toBeNull()
    expect(getResumeFileValidationError(file('resume.docx', 1024))).toBeNull()
  })

  it('rejects unsupported file types', () => {
    expect(getResumeFileValidationError(file('resume.txt', 1024))).toBe('仅支持 PDF、DOC、DOCX 简历文件')
  })

  it('rejects files larger than 10 MB', () => {
    expect(getResumeFileValidationError(file('resume.pdf', 10 * 1024 * 1024 + 1))).toBe('简历文件大小不能超过 10 MB')
  })
})
