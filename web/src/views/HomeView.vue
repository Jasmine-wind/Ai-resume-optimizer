<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import SkeletonBlock from '@/components/common/SkeletonBlock.vue'
import { retryJobAnalysis, startJobAnalysis } from '@/api/job-analysis'
import {
  getAiProviderSettings,
  resolveAiProviderConfigurationState,
  type AiProviderConfigurationState,
} from '@/api/ai-provider'
import { getJobDirectionInsights } from '@/api/job-direction-insight'
import { deleteOptimizationTask, getRecentOptimizationTasks } from '@/api/optimization-tasks'
import type { OptimizationTask } from '@/types/optimization-task'
import { getResumeList, requestResumePreparation, uploadResume } from '@/api/resume'
import { startAsyncTaskPolling } from '@/utils/asyncTaskPolling'
import type { AsyncTaskPollingController } from '@/utils/asyncTaskPolling'
import type { ActiveJobAnalysis, JobAnalysisStartResult } from '@/types/job-analysis'
import type { AsyncTaskVO } from '@/types/task'
import type { ResumeListItem } from '@/types/resume'
import {
  getResumeFileValidationError,
  getResumeStatus,
  getStartBlockMessage,
  getStartBlockReason,
  pickInitialResumeId,
} from './homeComposer'
import { resolveSafeRedirect } from '@/utils/safeRedirect'

const ACTIVE_ANALYSIS_STORAGE_KEY = 'cv-role:active-job-analysis'
const ANALYSIS_TIMEOUT_MS = 8 * 60 * 1000

type StoredActiveJobAnalysis = ActiveJobAnalysis & {
  resumeId?: number
  jobDescription?: string
}

const route = useRoute()
const router = useRouter()
const resumes = ref<ResumeListItem[]>([])
const selectedResumeId = ref<number | null>(null)
const jobDescription = ref('')
const loading = ref(false)
const loadFailed = ref(false)
const uploading = ref(false)
const uploadRowVisible = ref(false)
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const activeAnalysis = ref<JobAnalysisStartResult | null>(null)
const analysisTask = ref<AsyncTaskVO | null>(null)
const analysisError = ref<string | null>(null)
const analysisTimedOut = ref(false)
const startingAnalysis = ref(false)
const preparationTaskIds = ref<Record<number, number>>({})
const preparationMessages = ref<Record<number, string>>({})
const hasJobDirectionInsight = ref(false)
const aiConfigurationState = ref<AiProviderConfigurationState | null>(null)
const aiConfigurationRequired = ref(false)
const recentTasks = ref<OptimizationTask[]>([])
const recentTasksFailed = ref(false)
const recentTasksLoading = ref(false)
const deletingTaskId = ref<number | null>(null)
let analysisPolling: AsyncTaskPollingController | null = null
const preparationPolling = new Map<number, AsyncTaskPollingController>()

const selectedResume = computed(
  () => resumes.value.find((item) => item.id === selectedResumeId.value) ?? null,
)
const displayNameFor = (resume: ResumeListItem) => {
  if (resume.displayName?.trim()) return resume.displayName.trim()
  return resume.originalFilename.replace(/\.[^.]+$/u, '') || '未命名简历'
}
const preferredResumeId = computed(() => {
  const raw = Array.isArray(route.query.resumeId) ? route.query.resumeId[0] : route.query.resumeId
  const parsed = Number(raw)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined
})
const preparationTaskId = computed(() =>
  selectedResumeId.value == null
    ? null
    : (preparationTaskIds.value[selectedResumeId.value] ?? null),
)
const preparationMessage = computed(() =>
  selectedResumeId.value == null
    ? null
    : (preparationMessages.value[selectedResumeId.value] ?? null),
)
const selectedResumeStatus = computed(() =>
  getResumeStatus(selectedResume.value, preparationTaskId.value, preparationMessage.value),
)
const analysisRunning = computed(() => {
  const status = analysisTask.value?.status
  return Boolean(activeAnalysis.value && (!status || status === 'PENDING' || status === 'RUNNING'))
})
const requiresFreshAnalysis = computed(() =>
  analysisTask.value?.errorCode === 'CREDENTIAL_CHANGED'
  || (analysisTask.value?.errorCode === 'CONFIGURATION_INVALID'
    && analysisTask.value?.errorMessage === 'AI Credential 加密配置不可用'),
)
const startBlockReason = computed(() =>
  getStartBlockReason({
    resume: selectedResume.value,
    jobDescription: jobDescription.value,
    preparationTaskId: preparationTaskId.value,
    analysisRunning: analysisRunning.value,
    startingAnalysis: startingAnalysis.value,
    aiConfigurationState: aiConfigurationState.value,
  }),
)
const requiresAiConfiguration = computed(() =>
  aiConfigurationRequired.value || startBlockReason.value === 'AI 尚未配置' || startBlockReason.value === 'AI 配置尚未启用',
)
const aiSettingsTarget = computed(() => ({
  name: 'ai-provider-settings' as const,
  query: { redirect: resolveSafeRedirect('/app') },
}))
const canStart = computed(() => !startBlockReason.value)
const currentStage = computed(() => analysisTask.value?.message || '正在保存你的简历和目标岗位')
const analysisStateTitle = computed(() => {
  if (analysisTimedOut.value) return '岗位分析仍在后台进行'
  if (analysisTask.value?.status === 'CANCELLED') return '任务已取消'
  return '岗位分析没有完成'
})
const selectedResumeFilename = computed(() =>
  selectedResume.value ? displayNameFor(selectedResume.value) : '当前简历',
)
const composerActionLabel = computed(() => (canStart.value ? '本次核对' : '下一步'))
const composerActionText = computed(() => {
  if (canStart.value) return `将使用「${selectedResumeFilename.value}」核对这份岗位描述。`
  if (analysisRunning.value) return '当前岗位分析正在进行。'
  if (activeAnalysis.value && analysisError.value) return `${analysisStateTitle.value}。`
  if (startBlockReason.value === '请粘贴目标岗位 JD') return '粘贴完整岗位描述后即可开始核对。'
  return getStartBlockMessage(startBlockReason.value)
})
const composerActionDetail = computed(() => {
  if (canStart.value) return '只依据当前简历材料。'
  if (startBlockReason.value === '请粘贴目标岗位 JD') return ''
  if (analysisRunning.value) return currentStage.value
  if (activeAnalysis.value && analysisError.value) {
    if (requiresFreshAnalysis.value) return 'AI 配置已经更换，将使用当前配置重新创建任务；简历和岗位信息会保留。'
    return analysisTimedOut.value
      ? '可以继续等待，或稍后回到首页查看。'
      : '当前任务已保留，可以直接重试。'
  }
  if (selectedResumeStatus.value.kind === 'needs-review')
    return '前往确认后，这份简历才能用于岗位分析。'
  if (
    selectedResumeStatus.value.kind === 'failed' ||
    selectedResumeStatus.value.kind === 'reparse'
  ) {
    return '可以前往我的简历处理，完成后再回来。'
  }
  if (
    selectedResumeStatus.value.kind === 'preparing' ||
    selectedResumeStatus.value.kind === 'pending'
  ) {
    return selectedResumeStatus.value.description
  }
  return '完成这一步后，开始核对岗位要求。'
})

const loadAiConfiguration = async () => {
  try {
    const settings = await getAiProviderSettings()
    aiConfigurationState.value = resolveAiProviderConfigurationState(settings)
    aiConfigurationRequired.value = aiConfigurationState.value !== 'ACTIVE'
  } catch {
    // If the capability check is unavailable, let the start endpoint remain authoritative.
    aiConfigurationState.value = null
    aiConfigurationRequired.value = false
  }
}

const loadInsightAvailability = async () => {
  try {
    hasJobDirectionInsight.value = (await getJobDirectionInsights()).cohorts.length > 0
  } catch {
    // Insight is optional long-term value. Its read failure must never block the single-JD start flow.
    hasJobDirectionInsight.value = false
  }
}

const loadResumes = async (preferredResumeId?: number) => {
  loading.value = true
  loadFailed.value = false
  try {
    resumes.value = await getResumeList()
    if (!resumes.value.length) {
      uploadRowVisible.value = true
    }
    selectedResumeId.value = pickInitialResumeId(
      resumes.value,
      selectedResumeId.value,
      preferredResumeId,
    )
  } catch {
    // 区分真正的加载失败与空数据：失败提供重试，而不是当成没有简历。
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

const validateFile = (file: File) => {
  const validationError = getResumeFileValidationError(file)
  if (validationError) {
    ElMessage.error(validationError)
    return false
  }
  return true
}

const handleFileChange = (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  selectedFile.value = file && validateFile(file) ? file : null
  if (!selectedFile.value) {
    input.value = ''
  }
}

const clearJobDescription = async () => {
  if (!jobDescription.value.trim()) return
  try {
    await ElMessageBox.confirm('清空后，这份岗位描述将无法恢复。', '确认清空岗位描述？', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '保留',
    })
    jobDescription.value = ''
  } catch {
    // Cancelling the confirmation is an intentional no-op.
  }
}

const statusForResume = (resume: ResumeListItem) =>
  getResumeStatus(resume, preparationTaskIds.value[resume.id], preparationMessages.value[resume.id])

const clearPreparationState = (resumeId: number) => {
  const nextTaskIds = { ...preparationTaskIds.value }
  delete nextTaskIds[resumeId]
  preparationTaskIds.value = nextTaskIds
  const nextMessages = { ...preparationMessages.value }
  delete nextMessages[resumeId]
  preparationMessages.value = nextMessages
  preparationPolling.delete(resumeId)
}

const startPreparationPolling = (resumeId: number, taskId: number) => {
  preparationPolling.get(resumeId)?.stop()
  preparationTaskIds.value = { ...preparationTaskIds.value, [resumeId]: taskId }
  preparationMessages.value = { ...preparationMessages.value, [resumeId]: '正在读取简历内容' }
  const controller = startAsyncTaskPolling({
    taskId,
    timeoutMs: 5 * 60 * 1000,
    onUpdate: (task) => {
      preparationMessages.value = {
        ...preparationMessages.value,
        [resumeId]: task.message || '正在准备简历',
      }
    },
    onSuccess: async () => {
      clearPreparationState(resumeId)
      await loadResumes(resumeId)
      ElMessage.success('简历已准备好，可以开始分析')
    },
    onFailed: async (task) => {
      clearPreparationState(resumeId)
      await loadResumes(resumeId)
      ElMessage.error(task.errorMessage || '未能读取简历，请前往“我的简历”重试')
    },
    onTimeout: () => {
      clearPreparationState(resumeId)
      ElMessage.warning('简历仍在后台准备，请稍后再开始分析')
    },
    onError: (error) => {
      clearPreparationState(resumeId)
      ElMessage.error(error instanceof Error ? error.message : '获取简历状态失败')
    },
  })
  preparationPolling.set(resumeId, controller)
}

const handleUpload = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择简历文件')
    return
  }

  uploading.value = true
  try {
    const uploaded = await uploadResume(selectedFile.value)
    selectedFile.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    uploadRowVisible.value = false
    await loadResumes(uploaded.id)
    if (uploaded.preparationTaskId) {
      startPreparationPolling(uploaded.id, uploaded.preparationTaskId)
    } else {
      try {
        const task = await requestResumePreparation(uploaded.id)
        startPreparationPolling(uploaded.id, task.taskId)
      } catch {
        ElMessage.warning('简历已上传；如果暂时无法准备，开始分析时系统会自动重试')
      }
    }
    ElMessage.success('简历上传成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '简历上传失败')
  } finally {
    uploading.value = false
  }
}

const saveActiveAnalysis = (analysis: JobAnalysisStartResult) => {
  const stored: StoredActiveJobAnalysis = {
    ...analysis,
    resumeId: selectedResumeId.value ?? undefined,
    jobDescription: jobDescription.value.trim(),
    startedAt: new Date().toISOString(),
  }
  window.sessionStorage.setItem(ACTIVE_ANALYSIS_STORAGE_KEY, JSON.stringify(stored))
}

const clearActiveAnalysis = () => {
  window.sessionStorage.removeItem(ACTIVE_ANALYSIS_STORAGE_KEY)
  activeAnalysis.value = null
  analysisTask.value = null
}

const openAnalysisResult = (analysis: JobAnalysisStartResult) => {
  clearActiveAnalysis()
  router.push({
    name: 'job-analysis',
    params: {
      optimizationTaskId: String(analysis.optimizationTaskId),
    },
  })
}

const startAnalysisPolling = (analysis: JobAnalysisStartResult) => {
  analysisPolling?.stop()
  activeAnalysis.value = analysis
  analysisTask.value = null
  analysisError.value = null
  analysisTimedOut.value = false

  analysisPolling = startAsyncTaskPolling({
    taskId: analysis.taskId,
    timeoutMs: ANALYSIS_TIMEOUT_MS,
    onUpdate: (task) => {
      analysisTask.value = task
    },
    onSuccess: () => openAnalysisResult(analysis),
    onFailed: (task) => {
      analysisTask.value = task
      analysisTimedOut.value = false
      analysisError.value = task.errorMessage || '岗位分析没有完成。'
    },
    onCancelled: () => {
      analysisTimedOut.value = false
      analysisError.value = '岗位分析已取消，可以重新开始。'
    },
    onTimeout: () => {
      analysisTimedOut.value = true
      analysisError.value = '岗位分析仍在后台进行，你可以继续等待或稍后回到首页查看。'
    },
    onError: (error) => {
      const message = error instanceof Error ? error.message : '获取岗位分析状态失败'
      if (message.includes('任务不存在') || message.includes('无权限')) {
        clearActiveAnalysis()
        analysisError.value = null
        ElMessage.warning('上次岗位分析已不可用，请重新开始')
        return
      }
      analysisTimedOut.value = true
      analysisError.value = message
    },
  })
}

const handleStartAnalysis = async () => {
  if (!canStart.value || !selectedResumeId.value) {
    return
  }

  analysisError.value = null
  analysisTimedOut.value = false
  startingAnalysis.value = true
  try {
    const analysis = await startJobAnalysis({
      resumeId: selectedResumeId.value,
      jobDescription: jobDescription.value.trim(),
    })
    saveActiveAnalysis(analysis)
    startAnalysisPolling(analysis)
  } catch (error) {
    const failureCode = (error as { failureCode?: string }).failureCode
    aiConfigurationRequired.value = failureCode === 'AI_CONFIGURATION_REQUIRED'
    if (aiConfigurationRequired.value) aiConfigurationState.value = null
    analysisError.value = error instanceof Error ? error.message : '岗位分析启动失败'
  } finally {
    startingAnalysis.value = false
  }
}

const retryAnalysis = async () => {
  const analysis = activeAnalysis.value
  if (!analysis || startingAnalysis.value) {
    return
  }

  startingAnalysis.value = true
  analysisError.value = null
  analysisTimedOut.value = false
  try {
    const retried = await retryJobAnalysis(analysis.optimizationTaskId)
    saveActiveAnalysis(retried)
    startAnalysisPolling(retried)
  } catch (error) {
    analysisError.value = error instanceof Error ? error.message : '岗位分析重试失败'
  } finally {
    startingAnalysis.value = false
  }
}

const restartWithCurrentConfiguration = async () => {
  if (!activeAnalysis.value || startingAnalysis.value) return
  analysisPolling?.stop()
  clearActiveAnalysis()
  await handleStartAnalysis()
}

const continueWaiting = () => {
  if (!activeAnalysis.value) {
    return
  }
  startAnalysisPolling(activeAnalysis.value)
}

const restoreActiveAnalysis = () => {
  const raw = window.sessionStorage.getItem(ACTIVE_ANALYSIS_STORAGE_KEY)
  if (!raw) {
    return
  }
  try {
    const stored = JSON.parse(raw) as StoredActiveJobAnalysis
    if (
      stored.taskId &&
      stored.optimizationTaskId &&
      stored.sourceResumeVersionId &&
      stored.targetResumeVersionId &&
      stored.jobTargetId
    ) {
      if (stored.resumeId && resumes.value.some((resume) => resume.id === stored.resumeId)) {
        selectedResumeId.value = stored.resumeId
      }
      if (typeof stored.jobDescription === 'string') {
        jobDescription.value = stored.jobDescription
      }
      startAnalysisPolling(stored)
      return
    }
  } catch {
    // Invalid local state should not block a new analysis.
  }
  window.sessionStorage.removeItem(ACTIVE_ANALYSIS_STORAGE_KEY)
}

const loadRecentTasks = async () => {
  recentTasksLoading.value = true
  recentTasksFailed.value = false
  try {
    recentTasks.value = await getRecentOptimizationTasks()
  } catch {
    recentTasksFailed.value = true
  } finally {
    recentTasksLoading.value = false
  }
}
const taskStatusLabel = (status: string) =>
  ({
    PENDING: '分析中',
    RUNNING: '分析中',
    SUCCESS: '已完成',
    FAILED: '分析失败',
    CANCELLED: '已取消',
  })[status] || '分析中'
const taskStatusTone = (status: string) =>
  ({
    PENDING: 'pending',
    RUNNING: 'running',
    SUCCESS: 'success',
    FAILED: 'failed',
    CANCELLED: 'cancelled',
  })[status] || 'pending'
const taskTime = (value?: string) =>
  value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value),
      )
    : ''

const deleteRecentTask = async (task: OptimizationTask) => {
  if (deletingTaskId.value !== null) return
  try {
    await ElMessageBox.confirm(
      '删除后将无法从“最近优化”继续打开该岗位版本。原始简历不会被删除。',
      '删除这条岗位优化记录？',
      {
        type: 'warning',
        confirmButtonText: '删除记录',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  deletingTaskId.value = task.optimizationTaskId
  try {
    await deleteOptimizationTask(task.optimizationTaskId)
    recentTasks.value = recentTasks.value.filter(
      (item) => item.optimizationTaskId !== task.optimizationTaskId,
    )
    ElMessage.success('已删除岗位优化记录')
  } catch {
    ElMessage.error('删除失败，请稍后重试')
  } finally {
    deletingTaskId.value = null
  }
}

onMounted(async () => {
  await loadResumes(preferredResumeId.value)
  const pendingResumes = resumes.value.filter(
    (resume) => resume.parseStatus === 'PENDING' || resume.qualityStatus === 'PENDING',
  )
  await Promise.all(
    pendingResumes.map(async (resume) => {
      try {
        const task = await requestResumePreparation(resume.id)
        startPreparationPolling(resume.id, task.taskId)
      } catch {
        // The resume page exposes an explicit retry when the background task cannot be resumed.
      }
    }),
  )
  restoreActiveAnalysis()
  void loadAiConfiguration()
  void loadInsightAvailability()
  void loadRecentTasks()
})

onUnmounted(() => {
  analysisPolling?.stop()
  preparationPolling.forEach((controller) => controller.stop())
  preparationPolling.clear()
})
</script>

<template>
  <section class="home-page">
    <PageHeader
      title="针对一个岗位优化简历"
      description="选择一份已确认的简历，粘贴目标岗位 JD，系统会逐条核对岗位要求与当前材料。"
    />
    <SkeletonBlock v-if="loading && !resumes.length" title :rows="5" />

    <ErrorState
      v-else-if="loadFailed"
      title="简历列表加载失败"
      description="暂时无法读取你的简历列表，这不影响已保存的简历。"
      action-text="重新加载"
      @action="loadResumes()"
    />

    <section v-else class="home-composer" aria-label="岗位任务创建工作区">
      <div class="home-composer-grid">
        <section class="home-source-column" aria-labelledby="home-source-title">
          <header class="home-section-heading">
            <div>
              <span class="home-section-index">01</span>
              <span class="home-section-label">真实简历材料</span>
            </div>
            <RouterLink class="home-management-link" to="/resumes"
              >管理简历 <span aria-hidden="true">→</span></RouterLink
            >
            <h3 id="home-source-title">选择用于本次核对的简历</h3>
            <p>本次分析只使用这里选中的简历内容。</p>
          </header>

          <div
            v-if="resumes.length"
            class="home-resume-list"
            role="radiogroup"
            aria-labelledby="home-source-title"
          >
            <label
              v-for="resume in resumes"
              :key="resume.id"
              class="home-resume-option"
              :class="{ 'is-selected': selectedResumeId === resume.id }"
              :title="resume.originalFilename"
            >
              <input
                v-model="selectedResumeId"
                type="radio"
                name="home-resume"
                :value="resume.id"
                :disabled="analysisRunning || startingAnalysis"
              />
              <span class="home-option-radio" aria-hidden="true" />
              <span class="home-option-copy">
                <strong>{{ displayNameFor(resume) }}</strong>
                <small
                  >{{ resume.fileType }} · {{ statusForResume(resume).label }} ·
                  {{ resume.originalFilename }}</small
                >
              </span>
            </label>
          </div>

          <div v-else class="home-empty-source">
            <span class="home-empty-mark" aria-hidden="true">＋</span>
            <strong>还没有可用简历</strong>
            <p>先上传一份真实简历，系统会自动读取并准备内容。</p>
          </div>

          <div
            v-if="selectedResume && selectedResumeStatus.kind !== 'ready'"
            class="home-resume-state"
            role="status"
            aria-live="polite"
          >
            <span
              class="home-state-mark"
              :class="`is-${selectedResumeStatus.kind}`"
              aria-hidden="true"
            />
            <div>
              <strong>{{ selectedResumeStatus.label }}</strong>
              <p>{{ selectedResumeStatus.description }}</p>
              <RouterLink
                v-if="
                  selectedResumeStatus.kind === 'needs-review' ||
                  selectedResumeStatus.kind === 'failed' ||
                  selectedResumeStatus.kind === 'reparse'
                "
                class="home-inline-link"
                to="/resumes"
              >
                {{ selectedResumeStatus.kind === 'needs-review' ? '前往确认' : '前往我的简历处理' }}
                <span aria-hidden="true">→</span>
              </RouterLink>
            </div>
          </div>

          <button
            type="button"
            class="home-upload-trigger"
            :disabled="analysisRunning || startingAnalysis"
            :aria-expanded="uploadRowVisible"
            aria-controls="home-inline-upload"
            @click="uploadRowVisible = !uploadRowVisible"
          >
            <span aria-hidden="true">{{ uploadRowVisible ? '−' : '＋' }}</span>
            {{ uploadRowVisible ? '收起上传' : resumes.length ? '上传一份简历' : '上传第一份简历' }}
          </button>

          <div v-if="uploadRowVisible" id="home-inline-upload" class="home-inline-upload">
            <p>支持 PDF、DOC、DOCX，单份最大 10 MB。</p>
            <label class="home-file-picker">
              <span>{{ selectedFile?.name || '选择简历文件' }}</span>
              <input
                ref="fileInput"
                data-testid="home-resume-upload"
                type="file"
                accept=".pdf,.doc,.docx"
                @change="handleFileChange"
              />
            </label>
            <el-button
              type="primary"
              :loading="uploading"
              :disabled="!selectedFile"
              @click="handleUpload"
            >
              上传简历
            </el-button>
          </div>
        </section>

        <section class="home-target-column" aria-labelledby="home-target-title">
          <header class="home-section-heading">
            <div>
              <span class="home-section-index">02</span>
              <span class="home-section-label">目标岗位</span>
            </div>
            <button
              v-if="jobDescription.trim()"
              type="button"
              class="home-clear-link"
              @click="clearJobDescription"
            >
              清空
            </button>
            <h3 id="home-target-title">粘贴完整岗位描述</h3>
            <p>包含职责、要求和加分项，系统会逐条回到当前简历核对。</p>
          </header>

          <div class="home-jd-field">
            <label class="home-jd-label home-sr-only" for="home-jd">目标岗位 JD</label>
            <el-input
              id="home-jd"
              v-model="jobDescription"
              type="textarea"
              :rows="10"
              maxlength="10000"
              show-word-limit
              resize="vertical"
              aria-describedby="home-jd-boundary"
              placeholder="在这里粘贴完整岗位描述……

建议包含：
岗位职责
任职要求
加分项"
              :disabled="analysisRunning || startingAnalysis"
            />
            <p id="home-jd-boundary" class="home-field-boundary">
              只保存你提供的岗位描述，不会自动改写或补充内容。
            </p>
          </div>
        </section>
      </div>

      <footer class="home-task-bar">
        <div id="home-start-status" class="home-action-summary" aria-live="polite">
          <span
            v-if="analysisRunning || (activeAnalysis && analysisError)"
            class="home-action-label"
            >{{ composerActionLabel }}</span
          >
          <strong>{{ requiresAiConfiguration ? '开始优化前，请先配置并启用自己的 AI API。' : composerActionText }}</strong>
          <span v-if="composerActionDetail">{{ composerActionDetail }}</span>
        </div>
        <div class="home-start-actions-buttons">
          <RouterLink v-if="requiresAiConfiguration" :to="aiSettingsTarget">
            <el-button data-testid="home-configure-ai" type="primary" size="large">
              配置 AI →
            </el-button>
          </RouterLink>
          <el-button
            v-else
            data-testid="home-start-analysis"
            type="primary"
            size="large"
            :loading="startingAnalysis || analysisRunning"
            :disabled="!canStart"
            aria-describedby="home-start-status"
            @click="handleStartAnalysis"
          >
            {{
              startingAnalysis ? '正在启动…' : analysisRunning ? '正在核对…' : '开始核对岗位要求 →'
            }}
          </el-button>
        </div>
      </footer>

      <div
        v-if="analysisError && !activeAnalysis"
        class="home-analysis-state is-error"
        role="alert"
      >
        <span class="home-state-mark is-failed" aria-hidden="true" />
        <div>
          <strong>岗位分析启动失败</strong>
          <p>{{ analysisError }} 当前选择和岗位 JD 仍保留，可以直接重试。</p>
          <div class="home-state-actions">
            <el-button
              type="primary"
              plain
              :loading="startingAnalysis"
              @click="handleStartAnalysis"
            >
              重新开始
            </el-button>
          </div>
        </div>
      </div>

      <div
        v-else-if="activeAnalysis"
        class="home-analysis-state"
        :class="analysisError ? 'is-error' : 'is-running'"
        :role="analysisError ? 'alert' : 'status'"
        aria-live="polite"
      >
        <span
          class="home-state-mark"
          :class="analysisError ? 'is-failed' : 'is-running'"
          aria-hidden="true"
        />
        <div>
          <strong>{{ analysisError ? analysisStateTitle : '岗位分析正在后台进行' }}</strong>
          <p>{{ analysisError || currentStage }}</p>
          <p v-if="analysisError && !analysisTimedOut">
            {{ requiresFreshAnalysis ? 'AI 配置已经更换，需要使用当前配置重新创建任务；简历和岗位信息已保留。' : '简历和岗位信息已保存，无需重新填写。' }}
          </p>
          <div v-if="analysisError" class="home-state-actions">
            <el-button
              v-if="analysisTimedOut"
              plain
              :loading="startingAnalysis"
              @click="continueWaiting"
            >
              继续等待
            </el-button>
            <el-button
              v-else
              type="primary"
              plain
              :loading="startingAnalysis"
              @click="requiresFreshAnalysis ? restartWithCurrentConfiguration() : retryAnalysis()"
            >
              {{ requiresFreshAnalysis ? '使用当前配置重新开始' : '重试分析' }}
            </el-button>
          </div>
        </div>
      </div>
    </section>

    <section class="home-recent-section" aria-labelledby="recent-title">
      <h2 id="recent-title">最近优化</h2>
      <p v-if="recentTasksLoading">正在读取最近优化…</p>
      <p v-else-if="recentTasksFailed">
        暂时无法读取最近优化 <button type="button" @click="loadRecentTasks">重新加载</button>
      </p>
      <p v-else-if="!recentTasks.length">
        还没有岗位优化记录。完成第一次岗位分析后，可以从这里继续之前的工作。
      </p>
      <article v-for="task in recentTasks" :key="task.optimizationTaskId" class="recent-task">
        <div class="recent-task-copy">
          <div class="recent-task-primary">
            <strong>{{ task.jobTitle || '未命名岗位' }}</strong>
          </div>
          <span>{{ task.resumeName }} · {{ taskTime(task.updatedAt || task.createdAt) }}</span>
        </div>
        <div class="recent-task-actions">
          <small class="recent-task-status" :class="`is-${taskStatusTone(task.status)}`">{{
            taskStatusLabel(task.status)
          }}</small>
          <el-button
            v-if="task.status === 'SUCCESS'"
            link
            type="primary"
            :disabled="deletingTaskId === task.optimizationTaskId"
            @click="
              router.push({
                name: 'job-analysis',
                params: { optimizationTaskId: task.optimizationTaskId },
              })
            "
            >继续</el-button
          >
          <el-button
            v-else-if="task.status === 'FAILED'"
            link
            type="primary"
            :disabled="deletingTaskId === task.optimizationTaskId"
            @click="retryJobAnalysis(task.optimizationTaskId).then(loadRecentTasks)"
            >重新分析</el-button
          >
          <el-button
            link
            class="recent-task-delete"
            :loading="deletingTaskId === task.optimizationTaskId"
            :disabled="deletingTaskId !== null"
            :aria-label="`删除岗位优化记录：${task.jobTitle || '未命名岗位'}`"
            @click="deleteRecentTask(task)"
            >删除</el-button
          >
        </div>
      </article>
    </section>

    <section v-if="hasJobDirectionInsight" class="home-insight-row" aria-label="岗位方向洞察">
      <div>
        <strong>岗位方向洞察</strong>
        <span>查看这份简历在近期岗位中反复遇到的要求。</span>
      </div>
      <RouterLink class="home-insight-link" to="/job-direction-insights"
        >查看洞察 <span aria-hidden="true">→</span></RouterLink
      >
    </section>
  </section>
</template>

<style scoped>
.home-page {
  display: grid;
  gap: var(--app-section-spacing);
}

.home-composer {
  display: grid;
  border-top: 1px solid var(--app-border-strong);
  border-bottom: 1px solid var(--app-border-strong);
  background: var(--app-surface);
}

.home-composer-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.38fr) minmax(0, 0.62fr);
}

.home-source-column,
.home-target-column {
  display: grid;
  align-content: start;
  gap: var(--app-space-5);
  min-width: 0;
  padding: var(--app-space-8);
}

.home-source-column {
  border-right: 1px solid var(--app-border-strong);
}

.home-section-heading {
  display: grid;
  gap: var(--app-space-1);
}

.home-section-label {
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: var(--app-font-size-xs);
  font-weight: 700;
  letter-spacing: 0.06em;
}

.home-section-heading h3 {
  margin: 0;
  color: var(--app-text);
  font-size: 20px;
  line-height: var(--app-line-height-tight);
}

.home-section-heading p,
.home-field-boundary,
.home-target-empty {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.home-source-current {
  display: flex;
  gap: var(--app-space-3);
  align-items: flex-start;
  padding-bottom: var(--app-space-4);
  border-bottom: 1px solid var(--app-border);
}

.home-source-indicator,
.home-option-dot {
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--app-success);
}

.home-source-indicator {
  width: 8px;
  height: 8px;
  margin-top: 6px;
}

.home-source-current div,
.home-option-copy {
  display: grid;
  gap: var(--app-space-1);
  min-width: 0;
}

.home-source-current strong,
.home-option-copy strong {
  overflow: hidden;
  color: var(--app-text);
  font-size: var(--app-font-size-md);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-source-current span,
.home-option-copy small {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
}

.home-resume-options {
  display: grid;
  border-top: 1px solid var(--app-border-soft);
}

.home-options-label {
  padding: var(--app-space-3) 0 var(--app-space-1);
  color: var(--app-text-muted);
  font-size: var(--app-font-size-xs);
  font-weight: 700;
}

.home-resume-option {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  gap: var(--app-space-3);
  align-items: center;
  width: 100%;
  min-width: 0;
  border: 0;
  border-bottom: 1px solid var(--app-border-soft);
  padding: var(--app-space-3) 0;
  color: var(--app-text);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.home-resume-option:hover,
.home-resume-option:focus-visible {
  color: var(--app-text);
  background: var(--app-surface-soft);
}

.home-resume-option.is-selected {
  border-bottom-color: var(--app-primary-subtle);
  box-shadow: inset 3px 0 var(--app-primary);
  padding-left: var(--app-space-3);
  background: var(--app-primary-soft);
}

.home-option-dot {
  width: 6px;
  height: 6px;
  background: var(--app-border-strong);
}

.home-resume-option.is-selected .home-option-dot {
  background: var(--app-primary);
}

.home-upload-trigger {
  justify-self: start;
  border: 0;
  padding: 0;
  color: var(--app-primary-active);
  font: inherit;
  font-size: var(--app-font-size-sm);
  font-weight: 700;
  background: transparent;
  cursor: pointer;
}

.home-upload-trigger:hover,
.home-upload-trigger:focus-visible {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.home-upload-trigger:disabled {
  color: var(--app-text-muted);
  cursor: not-allowed;
}

.home-inline-upload {
  display: grid;
  gap: var(--app-space-3);
  padding: var(--app-space-4);
  border: 1px dashed var(--app-border-strong);
  background: var(--app-surface-soft);
}

.home-inline-upload p {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.home-file-picker {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  min-width: 0;
  padding: 0 var(--app-space-3);
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-sm);
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
  font-weight: 600;
  cursor: pointer;
  background: var(--app-surface);
}

.home-file-picker span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-file-picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.home-file-picker:focus-within {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.home-jd-field {
  display: grid;
  gap: var(--app-space-3);
  min-width: 0;
}

.home-jd-field :deep(.el-textarea__inner) {
  min-height: 250px !important;
  padding: var(--app-space-4);
  color: var(--app-text);
  font-size: var(--app-font-size-md);
  line-height: var(--app-line-height-body);
  background: var(--app-surface);
}

.home-field-boundary {
  font-size: var(--app-font-size-xs);
}

.home-target-empty {
  display: grid;
  min-height: 160px;
  place-items: center;
  border: 1px dashed var(--app-border);
  padding: var(--app-space-5);
  text-align: center;
  background: var(--app-surface-soft);
}

.home-analysis-state {
  display: flex;
  gap: var(--app-space-3);
  align-items: flex-start;
  padding: var(--app-space-4);
  border-top: 1px solid var(--app-border);
  background: var(--app-surface-soft);
}

.home-analysis-state.is-error {
  border-color: var(--el-color-danger-light-7);
  background: var(--app-danger-soft);
}

.home-state-dot {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  border-radius: 999px;
  flex: 0 0 auto;
  background: var(--app-primary);
}

.home-state-dot.is-static {
  background: var(--app-success);
}

.home-analysis-state.is-running .home-state-dot:not(.is-static) {
  animation: home-pulse 1.4s ease-in-out infinite;
}

.home-analysis-state.is-error .home-state-dot {
  background: var(--app-danger);
  animation: none;
}

.home-analysis-state > div {
  display: grid;
  gap: var(--app-space-2);
}

.home-analysis-state strong {
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
}

.home-analysis-state p {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
}

.home-state-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--app-space-2);
  margin-top: var(--app-space-1);
}

.home-task-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-5);
  padding: var(--app-space-4) var(--app-space-8);
  border-top: 1px solid var(--app-border-strong);
  background: var(--app-surface-soft);
}

.home-task-boundary {
  display: flex;
  gap: var(--app-space-2);
  align-items: center;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
}

.home-start-actions-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--app-space-2);
}

.home-recent-section {
  display: grid;
  gap: var(--app-space-3);
  border-bottom: 1px solid var(--app-border);
  padding: var(--app-space-4) 0;
}

.home-recent-section h2 {
  margin: 0;
  color: var(--app-text);
  font-size: var(--app-font-size-md);
}

.home-recent-section > p {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
}

.home-recent-section > p button {
  border: 0;
  padding: 0;
  color: var(--app-primary-active);
  font: inherit;
  font-weight: 700;
  background: transparent;
  cursor: pointer;
}

.recent-task {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-4);
  min-width: 0;
  border-top: 1px solid var(--app-border-soft);
  padding: var(--app-space-2) 0;
}

.recent-task-copy {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
  gap: 2px;
}

.recent-task-primary {
  display: block;
  min-width: 0;
}

.recent-task-actions {
  display: flex;
  flex: 0 1 auto;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: flex-start;
  min-width: 0;
  max-width: 100%;
  gap: var(--app-space-2) var(--app-space-3);
}

.recent-task-actions > .el-button {
  margin-left: 0;
}

.recent-task strong,
.recent-task span,
.recent-task small {
  overflow-wrap: anywhere;
}

.recent-task strong {
  color: var(--app-text);
  font-size: var(--app-font-size-md);
}
.recent-task span {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
}
.recent-task-status {
  flex: 0 0 auto;
  color: var(--app-text-muted);
  font-size: var(--app-font-size-xs);
  font-weight: 700;
  white-space: nowrap;
}

.recent-task-status.is-success {
  color: var(--app-status-supported);
}
.recent-task-status.is-pending,
.recent-task-status.is-running {
  color: var(--app-status-partial);
}
.recent-task-status.is-failed {
  color: var(--app-danger);
}
.recent-task-status.is-cancelled {
  color: var(--app-text-muted);
}

.recent-task-delete {
  color: var(--app-text-muted) !important;
  font-size: var(--app-font-size-xs);
  opacity: 0;
  pointer-events: none;
  transition:
    opacity 160ms ease,
    color 160ms ease;
}

.recent-task:hover .recent-task-delete,
.recent-task:focus-within .recent-task-delete {
  opacity: 1;
  pointer-events: auto;
}

.recent-task-delete:hover,
.recent-task-delete:focus-visible {
  color: var(--app-danger) !important;
}

.recent-task-delete:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

@media (hover: none), (pointer: coarse) {
  .recent-task-delete {
    opacity: 1;
    pointer-events: auto;
  }
}

.home-library-link,
.home-insight-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-5);
  border-bottom: 1px solid var(--app-border);
  padding: var(--app-space-4) 0;
}

.home-library-link > div,
.home-insight-row > div {
  display: flex;
  flex-wrap: wrap;
  gap: var(--app-space-2);
  align-items: baseline;
}

.home-library-link strong,
.home-insight-row strong {
  color: var(--app-text);
  font-size: var(--app-font-size-md);
}

.home-library-link span,
.home-insight-row span {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
}

@keyframes home-pulse {
  0%,
  100% {
    opacity: 0.4;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}

@media (max-width: 760px) {
  .home-composer-grid {
    grid-template-columns: 1fr;
  }

  .home-source-column {
    border-right: 0;
    border-bottom: 1px solid var(--app-border-strong);
  }

  .home-source-column,
  .home-target-column {
    padding: var(--app-space-5) var(--app-content-gutter-narrow);
  }

  .home-jd-field :deep(.el-textarea__inner) {
    min-height: 220px !important;
  }

  .home-task-bar,
  .home-library-link,
  .home-insight-row,
  .recent-task {
    align-items: stretch;
    flex-direction: column;
  }

  .recent-task-actions {
    align-self: flex-start;
  }

  .recent-task-delete {
    opacity: 1;
    pointer-events: auto;
  }

  .home-start-actions-buttons {
    justify-content: stretch;
  }

  .home-start-actions-buttons .el-button {
    flex: 1 1 auto;
  }
}

/* Job Target Composer: local page treatment, intentionally scoped to Home. */
.home-page {
  width: min(100%, 1280px);
  min-height: calc(100dvh - var(--app-shell-header-height) - 56px);
  margin: 0 auto;
  align-content: start;
  gap: 18px;
}

.home-page :deep(.ui-page-header) {
  margin-bottom: 0;
}

.home-page :deep(.ui-page-header h1) {
  font-size: clamp(28px, 2.4vw, 34px);
  letter-spacing: -0.035em;
}

.home-page :deep(.ui-page-header p:not(.ui-page-eyebrow)) {
  max-width: 650px;
  margin-top: 4px;
}

.home-composer {
  overflow: hidden;
  border: 1px solid var(--app-border-strong);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
  box-shadow: 0 8px 24px color-mix(in srgb, var(--app-text) 7%, transparent);
}

.home-composer-grid {
  grid-template-columns: minmax(300px, 0.38fr) minmax(0, 0.62fr);
  min-height: 0;
}

.home-source-column,
.home-target-column {
  gap: var(--app-space-4);
  padding: var(--app-space-6);
}

.home-source-column {
  border-right: 1px solid var(--app-border);
}

.home-target-column {
  background: color-mix(in srgb, var(--app-surface) 92%, var(--app-bg-soft));
}

.home-section-heading {
  position: relative;
  gap: 7px;
}

.home-section-heading > div:first-child {
  display: flex;
  gap: 10px;
  align-items: baseline;
}

.home-section-index {
  color: var(--app-primary);
  font-family: var(--app-font-mono);
  font-size: 13px;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.home-section-label {
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.home-section-heading h3 {
  max-width: 24ch;
  font-size: 21px;
  letter-spacing: -0.02em;
}

.home-section-heading p {
  max-width: 42ch;
}

.home-management-link,
.home-clear-link,
.home-inline-link,
.home-insight-link {
  color: var(--app-primary-active);
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
}

.home-management-link {
  position: absolute;
  top: 1px;
  right: 0;
}

.home-management-link:hover,
.home-management-link:focus-visible,
.home-clear-link:hover,
.home-clear-link:focus-visible,
.home-inline-link:hover,
.home-inline-link:focus-visible,
.home-insight-link:hover,
.home-insight-link:focus-visible {
  text-decoration: underline;
  text-underline-offset: 4px;
}

.home-clear-link {
  position: absolute;
  top: 1px;
  right: 0;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

.home-resume-list {
  display: grid;
  gap: 8px;
  max-height: 210px;
  overflow-y: auto;
  padding: 2px 3px 2px 2px;
}

.home-resume-option {
  position: relative;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 12px;
  min-height: 62px;
  box-sizing: border-box;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-sm);
  padding: 12px 13px;
  background: var(--app-surface);
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    box-shadow 160ms ease;
}

.home-resume-option input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.home-resume-option:has(input:focus-visible) {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.home-resume-option:hover {
  border-color: var(--app-border-strong);
  background: var(--app-surface-soft);
}

.home-resume-option.is-selected {
  border-color: var(--app-primary-subtle);
  box-shadow: inset 3px 0 var(--app-primary);
  background: color-mix(in srgb, var(--app-primary-soft) 52%, var(--app-surface));
}

.home-option-radio {
  width: 17px;
  height: 17px;
  box-sizing: border-box;
  border: 1px solid var(--app-border-strong);
  border-radius: 50%;
  background: var(--app-surface);
}

.home-resume-option.is-selected .home-option-radio {
  border: 5px solid var(--app-primary);
}

.home-option-copy strong {
  font-size: 14px;
}

.home-option-copy small {
  font-size: 11px;
}

.home-empty-source {
  display: grid;
  justify-items: start;
  align-content: center;
  min-height: 150px;
  border: 1px dashed var(--app-border-strong);
  border-radius: var(--app-radius-sm);
  padding: 20px;
  background: var(--app-surface-soft);
}

.home-empty-mark {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid var(--app-primary);
  border-radius: 50%;
  color: var(--app-primary);
  font-size: 18px;
}

.home-empty-source strong {
  margin-top: 12px;
  color: var(--app-text);
  font-size: 15px;
}

.home-empty-source p {
  margin: 5px 0 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.home-resume-state {
  display: flex;
  gap: 11px;
  align-items: flex-start;
  padding: 12px 13px;
  border-top: 1px solid var(--app-border);
  background: var(--app-surface-soft);
}

.home-resume-state > div {
  display: grid;
  gap: 4px;
}

.home-resume-state strong {
  color: var(--app-text);
  font-size: 12px;
}

.home-resume-state p {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 11px;
  line-height: 1.55;
}

.home-state-mark {
  width: 9px;
  height: 9px;
  margin-top: 4px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--app-primary);
}

.home-state-mark.is-ready {
  background: var(--app-success);
}
.home-state-mark.is-needs-review,
.home-state-mark.is-preparing,
.home-state-mark.is-pending,
.home-state-mark.is-reparse {
  background: var(--app-warning);
}
.home-state-mark.is-failed {
  background: var(--app-danger);
}
.home-state-mark.is-running {
  animation: home-pulse 1.4s ease-in-out infinite;
}

.home-upload-trigger {
  justify-self: start;
  min-height: 30px;
  color: var(--app-primary-active);
  font-size: 12px;
}

.home-inline-upload {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  padding: 13px;
  border: 1px dashed var(--app-border-strong);
  border-radius: var(--app-radius-sm);
}

.home-inline-upload p {
  grid-column: 1 / -1;
  font-size: 11px;
}

.home-file-picker {
  min-width: 0;
  font-size: 12px;
}

.home-inline-upload .el-button {
  min-height: 38px;
}

.home-jd-field {
  flex: 1;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-height: 0;
  gap: 9px;
}

.home-jd-label {
  color: var(--app-text);
  font-size: 12px;
  font-weight: 700;
}

.home-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  padding: 0;
  border: 0;
  margin: -1px;
  clip: rect(0, 0, 0, 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.home-jd-field :deep(.el-textarea),
.home-jd-field :deep(.el-textarea__inner) {
  height: 220px;
  min-height: 220px !important;
}

.home-jd-field :deep(.el-textarea__inner) {
  border-color: var(--app-border-strong);
  border-radius: var(--app-radius-sm);
  padding: 18px 44px 28px 20px;
  color: var(--app-text);
  font-size: 14px;
  line-height: 1.75;
  background: color-mix(in srgb, var(--app-bg-soft) 48%, var(--app-surface));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--app-border) 45%, transparent);
}

.home-jd-field :deep(.el-textarea__inner)::placeholder {
  color: var(--app-text-muted);
  opacity: 1;
}

.home-jd-field :deep(.el-textarea__inner):focus {
  border-color: var(--app-primary);
  box-shadow: inset 0 0 0 1px var(--app-primary);
}

.home-jd-field :deep(.el-input__count) {
  right: 13px;
  bottom: 9px;
  padding-left: 6px;
  background: transparent;
}

.home-field-boundary {
  font-size: 11px;
}

.home-task-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--app-space-6);
  padding: var(--app-space-4) var(--app-space-6);
  border-top: 1px solid var(--app-border-strong);
  background: var(--app-surface-soft);
}

.home-action-summary {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.home-action-label {
  color: var(--app-primary-active);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.home-action-summary strong {
  overflow: hidden;
  color: var(--app-text);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-action-summary > span:last-child {
  color: var(--app-text-muted);
  font-size: 11px;
}

.home-start-actions-buttons {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 7px;
}

.home-start-actions-buttons .el-button {
  min-height: 46px;
  padding-right: 22px;
  padding-left: 22px;
}

.home-composer > .home-analysis-state {
  border-top: 1px solid var(--app-border-strong);
  border-radius: 0;
  padding: 16px 32px;
}

.home-analysis-state {
  gap: 11px;
}

.home-analysis-state > div {
  gap: 5px;
}

.home-analysis-state strong {
  font-size: 12px;
}

.home-analysis-state p {
  font-size: 11px;
}

.home-insight-row {
  border-bottom: 1px solid var(--app-border);
  padding: 13px 0;
}

.home-insight-row > div {
  display: grid;
  gap: 3px;
}

.home-insight-row strong {
  font-size: 13px;
}

.home-insight-row span {
  font-size: 11px;
}

@media (max-width: 760px) {
  .home-page {
    min-height: 0;
    align-content: start;
    gap: 16px;
  }

  .home-page :deep(.ui-page-header h1) {
    font-size: 28px;
  }

  .home-composer-grid {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .home-source-column,
  .home-target-column {
    gap: 18px;
    padding: 22px 18px;
  }

  .home-source-column {
    border-right: 0;
    border-bottom: 1px solid var(--app-border);
  }

  .home-resume-list {
    max-height: none;
  }

  .home-jd-field :deep(.el-textarea),
  .home-jd-field :deep(.el-textarea__inner) {
    min-height: 220px !important;
  }

  .home-task-bar {
    grid-template-columns: 1fr;
    gap: 15px;
    padding: 17px 18px;
  }

  .home-action-summary strong {
    white-space: normal;
  }

  .home-resume-option {
    grid-template-columns: 18px minmax(0, 1fr);
  }

  .home-start-actions-buttons {
    align-items: stretch;
  }

  .home-start-actions-buttons .el-button {
    width: 100%;
  }

  .home-composer > .home-analysis-state {
    padding: 16px 18px;
  }

  .home-insight-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}

@media (min-width: 761px) and (max-height: 820px) {
  .home-page {
    min-height: 0;
    gap: 10px;
  }

  .home-composer-grid {
    min-height: 0;
  }

  .home-source-column,
  .home-target-column {
    gap: 14px;
    padding-top: 22px;
    padding-bottom: 22px;
  }

  .home-target-column {
    min-height: 0;
  }

  .home-jd-field {
    flex: none;
  }

  .home-jd-field :deep(.el-textarea),
  .home-jd-field :deep(.el-textarea__inner) {
    height: 230px;
    min-height: 230px !important;
  }

  .home-task-bar {
    gap: 16px;
    padding-top: 14px;
    padding-bottom: 14px;
  }
}

@media (max-width: 900px) {
  .home-page {
    min-height: 0;
    align-content: start;
    gap: 16px;
  }

  .home-composer-grid {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .home-source-column,
  .home-target-column {
    gap: 18px;
    padding: 22px 18px;
  }

  .home-source-column {
    border-right: 0;
    border-bottom: 1px solid var(--app-border);
  }

  .home-jd-field :deep(.el-textarea),
  .home-jd-field :deep(.el-textarea__inner) {
    height: 220px;
    min-height: 220px !important;
  }

  .home-task-bar {
    grid-template-columns: 1fr;
    gap: 15px;
    padding: 17px 18px;
  }

  .home-action-summary strong {
    white-space: normal;
  }

  .home-resume-option {
    grid-template-columns: 18px minmax(0, 1fr);
  }

  .home-start-actions-buttons {
    align-items: stretch;
  }

  .home-start-actions-buttons .el-button {
    width: 100%;
  }

  .home-composer > .home-analysis-state {
    padding: 16px 18px;
  }
}
</style>
