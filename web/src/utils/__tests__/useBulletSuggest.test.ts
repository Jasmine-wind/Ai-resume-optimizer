import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getWorkspaceContent,
  restorePreOptimizationContent,
  saveWorkspaceContent,
} from '@/api/workspace'
import type { ResumeDocument } from '@/types/resume-document'
import type { BulletSuggestionRequest, BulletSuggestionResult } from '@/types/workspace'
import { useBulletSuggest } from '@/utils/useBulletSuggest'
import { useWorkspaceEditor } from '@/utils/useWorkspaceEditor'

vi.mock('@/api/workspace', () => ({
  getWorkspaceContent: vi.fn(),
  saveWorkspaceContent: vi.fn(),
  restorePreOptimizationContent: vi.fn(),
  requestBulletSuggestion: vi.fn(),
}))

const getContentMock = vi.mocked(getWorkspaceContent)
const saveContentMock = vi.mocked(saveWorkspaceContent)
const restoreContentMock = vi.mocked(restorePreOptimizationContent)

const ORIGINAL = '负责订单服务开发'
const SUGGESTED = '承担订单服务开发工作'

const document = (text: string): ResumeDocument => ({
  schemaVersion: 'RESUME_DOCUMENT_V1',
  basics: { name: '张三', contacts: [] },
  sections: [
    {
      id: 's-1',
      kind: 'EXPERIENCE',
      title: '工作经历',
      entries: [
        {
          id: 'e-1',
          organization: '某公司',
          role: null,
          school: null,
          degree: null,
          major: null,
          startDate: null,
          endDate: null,
          location: null,
          group: null,
          skillItems: null,
          bullets: [{ id: 'b-1', text }],
        },
      ],
    },
  ],
})

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

const settle = async () => {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

const readyResult = (request: BulletSuggestionRequest): BulletSuggestionResult => ({
  requestId: request.requestId,
  state: 'READY',
  baseRevision: request.baseRevision,
  bulletId: request.bulletId,
  originalText: ORIGINAL,
  suggestedText: SUGGESTED,
  reason: '表达更完整',
  reviewCode: null,
  reviewMessage: null,
  rejectCode: null,
  rejectMessage: null,
  modelName: 'test-model',
})

const setup = () => {
  const editor = useWorkspaceEditor(10)
  const suggestMock =
    vi.fn<(taskId: number, request: BulletSuggestionRequest) => Promise<BulletSuggestionResult>>()
  const suggest = useBulletSuggest(10, editor, {
    api: { suggest: suggestMock },
    hashText: async (text) => `hash:${text}`,
  })
  return { editor, suggest, suggestMock }
}

describe('useBulletSuggest', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    getContentMock.mockResolvedValue({
      optimizationTaskId: 10,
      revision: 0,
      document: document(ORIGINAL),
    })
    saveContentMock.mockImplementation(async (_taskId, request) => ({
      saved: true,
      conflict: false,
      revision: request.expectedRevision + 1,
      document: request.document,
    }))
    restoreContentMock.mockResolvedValue({
      saved: true,
      conflict: false,
      revision: 1,
      document: document('恢复后的内容'),
    })
  })

  it('generates a suggestion and applies it as one undoable draft change', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.suggest('b-1', 'JOB_TARGETED')
    await settle()

    expect(suggest.phase.value).toBe('ready')
    expect(suggestMock).toHaveBeenCalledWith(
      10,
      expect.objectContaining({
        bulletId: 'b-1',
        baseRevision: 0,
        originalText: ORIGINAL,
        originalTextHash: `hash:${ORIGINAL}`,
        intent: 'JOB_TARGETED',
      }),
    )

    // Apply 前没有任何保存：Suggest 不写服务端。
    expect(saveContentMock).not.toHaveBeenCalled()

    expect(suggest.candidateValid.value).toBe(true)
    expect(suggest.apply()).toBe(true)

    const applied = editor.draft.value?.sections[0].entries[0].bullets[0].text
    expect(applied).toBe(SUGGESTED)
    expect(editor.status.value).not.toBe('saved')
    expect(editor.canUndo.value).toBe(true)

    // Apply 后进入既有 dirty → Auto Save → CAS。
    await vi.advanceTimersByTimeAsync(800)
    await settle()
    expect(saveContentMock).toHaveBeenCalledWith(
      10,
      expect.objectContaining({
        expectedRevision: 0,
        document: expect.objectContaining({
          sections: expect.arrayContaining([
            expect.objectContaining({
              entries: expect.arrayContaining([
                expect.objectContaining({
                  bullets: [{ id: 'b-1', text: SUGGESTED }],
                }),
              ]),
            }),
          ]),
        }),
      }),
    )

    // Apply 形成正常 Undo 节点。
    editor.undo()
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(ORIGINAL)
  })

  it('discards a no-op suggestion without exposing apply or changing the draft', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => ({
      ...readyResult(request),
      originalText: `  ${ORIGINAL}\n`,
      suggestedText: `\t${ORIGINAL}  `,
    }))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()

    expect(suggest.phase.value).toBe('idle')
    expect(suggest.candidate.value).toBeNull()
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(ORIGINAL)
    expect(editor.canUndo.value).toBe(false)
    expect(saveContentMock).not.toHaveBeenCalled()
  })

  it('keeps punctuation and meaningful text changes as real suggestions', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => ({
      ...readyResult(request),
      suggestedText: '负责订单 服务开发。',
    }))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()

    expect(suggest.phase.value).toBe('ready')
    expect(suggest.candidate.value?.suggestedText).toBe('负责订单 服务开发。')
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(ORIGINAL)
  })

  it('fails closed when the suggestion response is bound to another bullet or revision', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => ({
      ...readyResult(request),
      bulletId: 'another-bullet',
    }))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()

    expect(suggest.phase.value).toBe('error')
    expect(suggest.errorMessage.value).toBe('建议响应与当前内容不一致，已丢弃，请重试')
    expect(suggest.candidate.value).toBeNull()
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(ORIGINAL)
    expect(saveContentMock).not.toHaveBeenCalled()
  })

  it('rejects apply after the bullet was manually edited during generation', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    const pending = deferred<BulletSuggestionResult>()
    suggestMock.mockReturnValue(pending.promise)

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    expect(suggest.phase.value).toBe('requesting')

    // AI 请求期间人工编辑 Bullet。
    editor.applyDocument(document(`${ORIGINAL}（人工修改）`))
    await settle()

    const request = suggestMock.mock.calls[0][1]
    pending.resolve(readyResult(request))
    await settle()

    expect(suggest.phase.value).toBe('ready')
    expect(suggest.candidateValid.value).toBe(false)
    expect(suggest.candidateStale.value).toBe(true)
    expect(suggest.apply()).toBe(false)
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(
      `${ORIGINAL}（人工修改）`,
    )
  })

  it('marks candidate stale after undo or redo', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    expect(suggest.candidateValid.value).toBe(true)

    // 任意一次 Undo/Redo 都是草稿变更：候选失效。
    editor.applyDocument(document('临时改动'))
    editor.undo()
    await settle()

    expect(suggest.candidateValid.value).toBe(false)
    expect(suggest.apply()).toBe(false)
  })

  it('marks candidate stale when revision changes via save', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    expect(suggest.candidateValid.value).toBe(true)

    // 其它位置的编辑触发保存：revision 前进，候选绑定的 baseRevision 过期。
    const next = document(ORIGINAL)
    next.sections[0].title = '工作经历（改）'
    editor.applyDocument(next)
    await vi.advanceTimersByTimeAsync(800)
    await settle()
    expect(editor.revision.value).toBe(1)

    expect(suggest.candidateValid.value).toBe(false)
    expect(suggest.apply()).toBe(false)
  })

  it('marks candidate stale after restore', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    expect(suggest.candidateValid.value).toBe(true)

    await editor.restorePreOptimization()
    await settle()

    expect(suggest.candidateValid.value).toBe(false)
    expect(suggest.apply()).toBe(false)
  })

  it('ignores out-of-order responses and only honours the latest requestId', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    const first = deferred<BulletSuggestionResult>()
    const second = deferred<BulletSuggestionResult>()
    suggestMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    // Regenerate：旧 requestId 立刻失效。
    suggest.regenerate()
    await settle()

    const firstRequest = suggestMock.mock.calls[0][1]
    const secondRequest = suggestMock.mock.calls[1][1]
    expect(firstRequest.requestId).not.toBe(secondRequest.requestId)

    // 乱序：旧请求后到，不得覆盖新候选。
    second.resolve({
      ...readyResult(secondRequest),
      suggestedText: '第二次生成结果',
    })
    await settle()
    first.resolve({
      ...readyResult(firstRequest),
      suggestedText: '第一次生成结果',
    })
    await settle()

    expect(suggest.phase.value).toBe('ready')
    expect(suggest.candidate.value?.suggestedText).toBe('第二次生成结果')
    expect(suggest.candidate.value?.requestId).toBe(secondRequest.requestId)
  })

  it('reject has no side effects and never calls the server', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    const draftBefore = JSON.stringify(editor.draft.value)

    suggest.reject()
    await settle()

    expect(suggest.phase.value).toBe('idle')
    expect(JSON.stringify(editor.draft.value)).toBe(draftBefore)
    expect(saveContentMock).not.toHaveBeenCalled()
    // Reject 只发生在前端：除生成请求外没有任何服务端调用。
    expect(suggestMock).toHaveBeenCalledTimes(1)
  })

  it('keeps the draft untouched when AI fails', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockRejectedValue(new Error('AI 服务暂时不可用，请稍后重试'))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()

    expect(suggest.phase.value).toBe('error')
    expect(suggest.errorMessage.value).toBe('AI 服务暂时不可用，请稍后重试')
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(ORIGINAL)
    expect(saveContentMock).not.toHaveBeenCalled()
  })

  it('surfaces server-side fact rejection without offering apply', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => ({
      requestId: request.requestId,
      state: 'REJECTED',
      baseRevision: request.baseRevision,
      bulletId: request.bulletId,
      originalText: ORIGINAL,
      suggestedText: null,
      reason: null,
      reviewCode: null,
      reviewMessage: null,
      rejectCode: 'NEW_TECHNOLOGY',
      rejectMessage: '改写引入了原文没有的技术名称',
      modelName: 'test-model',
    }))

    suggest.suggest('b-1', 'JOB_TARGETED')
    await settle()

    expect(suggest.phase.value).toBe('rejected')
    expect(suggest.candidate.value).toBeNull()
    expect(suggest.rejectInfo.value?.code).toBe('NEW_TECHNOLOGY')
    expect(suggest.apply()).toBe(false)
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(ORIGINAL)
  })

  it('keeps local draft when the post-apply save hits a CAS conflict', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))
    saveContentMock.mockResolvedValue({
      saved: false,
      conflict: true,
      revision: 5,
      document: null,
    })

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    expect(suggest.apply()).toBe(true)

    await vi.advanceTimersByTimeAsync(800)
    await settle()

    // Phase 4 冲突规则不被绕过：保留本地草稿，进入 conflict。
    expect(editor.status.value).toBe('conflict')
    expect(editor.conflictRevision.value).toBe(5)
    expect(editor.draft.value?.sections[0].entries[0].bullets[0].text).toBe(SUGGESTED)
  })

  it('stops handling responses after dispose (route/task switch)', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    const pending = deferred<BulletSuggestionResult>()
    suggestMock.mockReturnValue(pending.promise)

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    suggest.dispose()

    const request = suggestMock.mock.calls[0][1]
    pending.resolve(readyResult(request))
    await settle()

    expect(suggest.phase.value).toBe('requesting')
    expect(suggest.candidate.value).toBeNull()
  })

  it('supports custom instruction flow', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.startCustomCompose('b-1')
    expect(suggest.phase.value).toBe('composing')

    suggest.submitCustom('b-1', '更突出后端职责')
    await settle()

    expect(suggestMock).toHaveBeenCalledWith(
      10,
      expect.objectContaining({
        intent: 'CUSTOM',
        userInstruction: '更突出后端职责',
      }),
    )
    expect(suggest.phase.value).toBe('ready')
  })

  it('invalidates candidate when a save conflict exists', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    suggestMock.mockImplementation(async (_taskId, request) => readyResult(request))

    suggest.suggest('b-1', 'SIMPLIFY')
    await settle()
    expect(suggest.candidateValid.value).toBe(true)

    // 不改动草稿，直接触发一次保存并冲突：只有 conflictRevision 置位，
    // revision / mutationSequence / Bullet 原文都不变，候选仍必须失效。
    saveContentMock.mockResolvedValueOnce({
      saved: false,
      conflict: true,
      revision: 9,
      document: null,
    })
    await editor.flushNow()
    await settle()
    expect(editor.status.value).toBe('conflict')
    expect(editor.revision.value).toBe(0)

    expect(suggest.candidateValid.value).toBe(false)
    expect(suggest.apply()).toBe(false)
  })

  it('keeps the suggestion card anchored to the bullet while requesting', async () => {
    const { editor, suggest, suggestMock } = setup()
    await editor.load()
    const pending = deferred<BulletSuggestionResult>()
    suggestMock.mockReturnValue(pending.promise)

    suggest.suggest('b-1', 'JOB_TARGETED')
    await settle()

    expect(suggest.phase.value).toBe('requesting')
    expect(suggest.activeBulletId.value).toBe('b-1')
    expect(suggest.busy.value).toBe(true)
  })
})
