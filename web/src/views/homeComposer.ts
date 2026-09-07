import type { ResumeListItem } from '@/types/resume'
import type { AiProviderConfigurationState } from '@/api/ai-provider'

export type ResumeStatusKind = 'ready' | 'preparing' | 'needs-review' | 'failed' | 'reparse' | 'pending' | 'empty'

export interface ResumeStatus {
  kind: ResumeStatusKind
  label: string
  description: string
}

export interface StartBlockReasonInput {
  resume: ResumeListItem | null
  jobDescription: string
  preparationTaskId?: number | null
  analysisRunning?: boolean
  startingAnalysis?: boolean
  aiConfigurationState?: AiProviderConfigurationState | null
}

export const MAX_RESUME_FILE_SIZE = 10 * 1024 * 1024
export const ACCEPTED_RESUME_EXTENSIONS = ['pdf', 'doc', 'docx'] as const

export const pickInitialResumeId = (
  resumes: ResumeListItem[],
  currentId: number | null,
  preferredId?: number,
) => {
  if (preferredId && resumes.some((resume) => resume.id === preferredId)) {
    return preferredId
  }
  if (currentId && resumes.some((resume) => resume.id === currentId)) {
    return currentId
  }
  const readyResume = resumes.find(
    (resume) =>
      resume.parseStatus === 'SUCCESS' &&
      resume.qualityStatus === 'READY' &&
      resume.canonicalReady !== false,
  )
  return readyResume?.id ?? resumes[0]?.id ?? null
}

export const getResumeStatus = (
  resume: ResumeListItem | null,
  preparationTaskId?: number | null,
  preparationMessage?: string | null,
): ResumeStatus => {
  if (!resume) {
    return { kind: 'empty', label: '未选择简历', description: '请先选择或上传一份真实简历。' }
  }
  if (resume.parseStatus === 'FAILED' || resume.qualityStatus === 'FAILED') {
    return {
      kind: 'failed',
      label: '准备失败',
      description: resume.parseErrorMessage || '这份简历暂时无法用于分析，请前往我的简历重试。',
    }
  }
  if (preparationTaskId || resume.parseStatus === 'PENDING' || resume.parseStatus === 'PROCESSING' || resume.qualityStatus === 'PENDING') {
    return {
      kind: 'preparing',
      label: preparationMessage || '正在准备',
      description: preparationMessage || '系统正在读取并整理这份简历。',
    }
  }
  if (resume.canonicalReady === false) {
    return {
      kind: 'reparse',
      label: '需要重新解析',
      description: '这份简历需要重新准备后才能开始岗位分析。',
    }
  }
  if (resume.qualityStatus === 'NEEDS_REVIEW') {
    return {
      kind: 'needs-review',
      label: '需要确认',
      description: '这份简历有内容需要确认，确认后才能用于岗位分析。',
    }
  }
  if (resume.parseStatus === 'SUCCESS' && resume.qualityStatus === 'READY') {
    return {
      kind: 'ready',
      label: '可用于分析',
      description: '这份简历已准备好，可以开始核对岗位要求。',
    }
  }
  return {
    kind: 'pending',
    label: '等待准备',
    description: '这份简历还没有完成准备。',
  }
}

export const getStartBlockMessage = (reason: string) => {
  switch (reason) {
    case '请先选择一份简历':
      return '请选择一份可以用于分析的简历。'
    case '当前简历仍在准备':
      return '这份简历仍在准备，请等待准备完成。'
    case '当前简历需要确认':
      return '这份简历有内容需要确认。'
    case '当前简历准备失败':
      return '这份简历准备失败，请先前往我的简历处理。'
    case '当前简历需要重新解析':
      return '这份简历需要重新解析，请先前往我的简历处理。'
    case '请粘贴目标岗位 JD':
      return '请粘贴完整的目标岗位描述。'
    case '岗位分析正在进行':
      return '当前岗位分析正在进行。'
    case '当前任务正在启动':
      return '当前任务正在启动。'
    case 'AI 尚未配置':
      return '开始优化前，请先配置并启用自己的 AI API。'
    case 'AI 配置尚未启用':
      return '请先启用已保存的 API，才能开始岗位分析。'
    default:
      return reason
  }
}

export const getStartBlockReason = ({
  resume,
  jobDescription,
  preparationTaskId,
  analysisRunning = false,
  startingAnalysis = false,
  aiConfigurationState = null,
}: StartBlockReasonInput) => {
  if (startingAnalysis) return '当前任务正在启动'
  if (analysisRunning) return '岗位分析正在进行'
  if (!resume) return '请先选择一份简历'

  const status = getResumeStatus(resume, preparationTaskId)
  if (status.kind === 'preparing' || status.kind === 'pending') return '当前简历仍在准备'
  if (status.kind === 'needs-review') return '当前简历需要确认'
  if (status.kind === 'failed') return '当前简历准备失败'
  if (status.kind === 'reparse') return '当前简历需要重新解析'
  if (!jobDescription.trim()) return '请粘贴目标岗位 JD'
  if (aiConfigurationState === 'UNCONFIGURED') return 'AI 尚未配置'
  if (aiConfigurationState === 'SAVED_DISABLED') return 'AI 配置尚未启用'
  return ''
}

export const getResumeFileValidationError = (file: File) => {
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  if (!ACCEPTED_RESUME_EXTENSIONS.includes(extension as (typeof ACCEPTED_RESUME_EXTENSIONS)[number])) {
    return '仅支持 PDF、DOC、DOCX 简历文件'
  }
  if (file.size > MAX_RESUME_FILE_SIZE) {
    return '简历文件大小不能超过 10 MB'
  }
  return null
}
