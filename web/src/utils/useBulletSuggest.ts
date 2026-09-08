import { computed, ref } from 'vue'
import { requestBulletSuggestion } from '@/api/workspace'
import type { ResumeDocument } from '@/types/resume-document'
import type {
  BulletSuggestIntent,
  BulletSuggestionRequest,
  BulletSuggestionResult,
} from '@/types/workspace'
import type { useWorkspaceEditor } from '@/utils/useWorkspaceEditor'

export type WorkspaceEditorHandle = ReturnType<typeof useWorkspaceEditor>

export type BulletSuggestPhase =
  | 'idle'
  | 'composing'
  | 'requesting'
  | 'ready'
  | 'rejected'
  | 'error'

export interface BulletSuggestionCandidate {
  requestId: string
  bulletId: string
  baseRevision: number
  sequence: number
  originalText: string
  suggestedText: string
  reason: string
  reviewCode: string | null
  reviewMessage: string | null
  modelName: string | null
}

export interface BulletSuggestApi {
  suggest: (
    optimizationTaskId: number,
    request: BulletSuggestionRequest,
  ) => Promise<BulletSuggestionResult>
}

export interface BulletSuggestOptions {
  api?: BulletSuggestApi
  hashText?: (text: string) => Promise<string>
}

const defaultApi: BulletSuggestApi = { suggest: requestBulletSuggestion }

/** 比较候选与原文时只忽略首尾和连续空白，不改变任何事实字符。 */
export const normalizeComparableBulletText = (text: string) => text.replace(/\s+/gu, ' ').trim()

const toHex = (bytes: ArrayBuffer) =>
  Array.from(new Uint8Array(bytes))
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('')

/** 原文去除首尾空白后的 SHA-256，与服务端哈希口径一致。 */
export const sha256Hex = async (text: string): Promise<string> => {
  const data = new TextEncoder().encode(text)
  const digest = await crypto.subtle.digest('SHA-256', data)
  return toHex(digest)
}

const findBulletText = (document: ResumeDocument | null, bulletId: string): string | null => {
  if (!document) return null
  for (const section of document.sections) {
    for (const entry of section.entries) {
      const bullet = entry.bullets.find((item) => item.id === bulletId)
      if (bullet) return bullet.text
    }
  }
  return null
}

/**
 * 单 Bullet AI 建议生命周期状态机。
 *
 * 候选绑定 requestId + baseRevision + mutationSequence + bulletId + originalText；
 * 请求发起后发生人工编辑 / Undo / Redo / Restore / revision 变化 / 切换 / Regenerate
 * 中任何一项，候选即失效且不可 Apply。Suggest / Reject / Regenerate 不修改草稿，
 * 只有显式 Apply 会替换对应 Bullet 文本并进入既有 Undo → dirty → Auto Save → CAS。
 */
export function useBulletSuggest(
  optimizationTaskId: number,
  editor: WorkspaceEditorHandle,
  options: BulletSuggestOptions = {},
) {
  const api = options.api ?? defaultApi
  const hashText = options.hashText ?? sha256Hex

  const phase = ref<BulletSuggestPhase>('idle')
  const candidate = ref<BulletSuggestionCandidate | null>(null)
  const rejectInfo = ref<{
    bulletId: string
    code: string | null
    message: string | null
  } | null>(null)
  const errorMessage = ref<string | null>(null)
  const composingBulletId = ref<string | null>(null)

  let latestRequestId: string | null = null
  let lastRequest: {
    bulletId: string
    intent: BulletSuggestIntent
    instruction: string | null
  } | null = null
  let disposed = false

  /**
   * 候选仍然有效：requestId 未被替代、revision 未变、无保存冲突、草稿未变、Bullet 原文未变。
   * conflict 期间任何写入都已被 Phase 4 阻断，候选同样不得 Apply。
   */
  const candidateValid = computed(() => {
    const current = candidate.value
    if (!current || phase.value !== 'ready') return false
    if (current.requestId !== latestRequestId) return false
    if (editor.revision.value === null || editor.revision.value !== current.baseRevision) {
      return false
    }
    if (editor.conflictRevision.value !== null) return false
    if (editor.mutationSequence.value !== current.sequence) return false
    const text = findBulletText(editor.draft.value, current.bulletId)
    return text !== null && text === current.originalText
  })

  const candidateStale = computed(() => phase.value === 'ready' && !candidateValid.value)

  const activeBulletId = computed<string | null>(() => {
    if (phase.value === 'composing') return composingBulletId.value
    if (candidate.value) return candidate.value.bulletId
    if (rejectInfo.value) return rejectInfo.value.bulletId
    // requesting / error 阶段还没有候选对象，回退到最后一次请求的 Bullet。
    return lastRequest?.bulletId ?? null
  })

  const busy = computed(() => phase.value === 'requesting')

  const resetToIdle = () => {
    candidate.value = null
    rejectInfo.value = null
    errorMessage.value = null
    composingBulletId.value = null
    latestRequestId = null
    lastRequest = null
    phase.value = 'idle'
  }

  const issue = async (
    bulletId: string,
    intent: BulletSuggestIntent,
    instruction: string | null,
  ) => {
    if (disposed) return
    const draft = editor.draft.value
    const baseRevision = editor.revision.value
    const originalText = findBulletText(draft, bulletId)
    if (!draft || baseRevision === null || originalText === null || originalText.trim() === '') {
      return
    }

    const requestId = crypto.randomUUID()
    // Regenerate / 新请求立即替代旧 requestId：旧候选与旧响应从此失效。
    latestRequestId = requestId
    lastRequest = { bulletId, intent, instruction }
    candidate.value = null
    rejectInfo.value = null
    errorMessage.value = null
    composingBulletId.value = null
    phase.value = 'requesting'

    const binding = {
      baseRevision,
      sequence: editor.mutationSequence.value,
      originalText,
    }

    try {
      const originalTextHash = await hashText(binding.originalText.trim())
      if (disposed || requestId !== latestRequestId) return
      const result = await api.suggest(optimizationTaskId, {
        requestId,
        bulletId,
        baseRevision: binding.baseRevision,
        originalText: binding.originalText,
        originalTextHash,
        intent,
        userInstruction: instruction,
      })
      if (disposed || result.requestId !== latestRequestId) return
      if (
        result.bulletId !== bulletId ||
        result.baseRevision !== binding.baseRevision ||
        typeof result.originalText !== 'string' ||
        normalizeComparableBulletText(result.originalText) !==
          normalizeComparableBulletText(binding.originalText)
      ) {
        errorMessage.value = '建议响应与当前内容不一致，已丢弃，请重试'
        phase.value = 'error'
        return
      }
      if (result.state === 'READY' && result.suggestedText !== null) {
        // AI 有时只改变换行或首尾空格；这种结果没有可供用户判断的修改，
        // 直接丢弃，不产生候选、Apply 或 Undo。
        if (
          normalizeComparableBulletText(binding.originalText) ===
          normalizeComparableBulletText(result.suggestedText)
        ) {
          resetToIdle()
          return
        }
        candidate.value = {
          requestId: result.requestId,
          bulletId,
          baseRevision: binding.baseRevision,
          sequence: binding.sequence,
          originalText: binding.originalText,
          suggestedText: result.suggestedText,
          reason: result.reason ?? '',
          reviewCode: result.reviewCode,
          reviewMessage: result.reviewMessage,
          modelName: result.modelName,
        }
        phase.value = 'ready'
      } else if (result.state === 'REJECTED') {
        rejectInfo.value = {
          bulletId,
          code: result.rejectCode,
          message: result.rejectMessage,
        }
        phase.value = 'rejected'
      } else {
        errorMessage.value = '建议响应格式无效，已丢弃，请重试'
        phase.value = 'error'
      }
    } catch (error) {
      if (disposed || requestId !== latestRequestId) return
      errorMessage.value = error instanceof Error ? error.message : 'AI 建议生成失败，请重试'
      phase.value = 'error'
    }
  }

  const suggest = (
    bulletId: string,
    intent: BulletSuggestIntent,
    instruction: string | null = null,
  ) => {
    void issue(bulletId, intent, instruction)
  }

  const startCustomCompose = (bulletId: string) => {
    if (disposed || busy.value) return
    candidate.value = null
    rejectInfo.value = null
    errorMessage.value = null
    composingBulletId.value = bulletId
    phase.value = 'composing'
  }

  const submitCustom = (bulletId: string, instruction: string) => {
    if (disposed || composingBulletId.value !== bulletId) return
    if (!instruction.trim()) return
    void issue(bulletId, 'CUSTOM', instruction.trim())
  }

  const cancelCompose = () => {
    if (phase.value !== 'composing') return
    resetToIdle()
  }

  /** Regenerate：用同一 Bullet / 意图 / 要求重新发起；旧 requestId 即刻失效，即使旧请求仍在途。 */
  const regenerate = () => {
    if (!lastRequest || disposed) return
    void issue(lastRequest.bulletId, lastRequest.intent, lastRequest.instruction)
  }

  /** Apply 前重新验证候选；只替换对应 Bullet 文本，形成一个正常 Undo 节点。 */
  const apply = (): boolean => {
    const current = candidate.value
    if (!current || !candidateValid.value || !editor.draft.value) return false
    const next = JSON.parse(JSON.stringify(editor.draft.value)) as ResumeDocument
    let replaced = false
    for (const section of next.sections) {
      for (const entry of section.entries) {
        const bullet = entry.bullets.find((item) => item.id === current.bulletId)
        if (bullet && bullet.text === current.originalText) {
          bullet.text = current.suggestedText
          replaced = true
          break
        }
      }
      if (replaced) break
    }
    if (!replaced) return false
    editor.applyDocument(next)
    resetToIdle()
    return true
  }

  /** Reject 是纯前端动作：不调用服务端，不产生任何副作用。 */
  const reject = () => {
    resetToIdle()
  }

  const dismiss = () => {
    resetToIdle()
  }

  const dispose = () => {
    disposed = true
  }

  return {
    phase,
    candidate,
    candidateValid,
    candidateStale,
    rejectInfo,
    errorMessage,
    composingBulletId,
    activeBulletId,
    busy,
    suggest,
    startCustomCompose,
    submitCustom,
    cancelCompose,
    regenerate,
    apply,
    reject,
    dismiss,
    dispose,
  }
}

export type BulletSuggestController = ReturnType<typeof useBulletSuggest>
