<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ErrorState from '@/components/common/ErrorState.vue'
import {
  deleteWorkspaceArtifact,
  downloadArtifactPdf,
  exportWorkspacePdf,
  listWorkspaceArtifacts,
  previewWorkspacePdf,
} from '@/api/workspace'
import type {
  ExportArtifact,
  ExportPreflight,
  ResumeTemplateId,
  WorkspaceSaveStatus,
} from '@/types/workspace'

const props = defineProps<{
  optimizationTaskId: number
  /** 最近一次成功保存的服务端内容版本号；null 表示内容尚未就绪。 */
  revision: number | null
  status: WorkspaceSaveStatus
}>()

/** revision 过期（其它端修改了内容）时通知工作区同步服务端版本。 */
const emit = defineEmits<{ stale: [] }>()

const TEMPLATE_OPTIONS: { value: ResumeTemplateId; label: string }[] = [
  { value: 'classic', label: '经典' },
  { value: 'modern', label: '现代' },
  { value: 'minimal', label: '简洁' },
]

const TEMPLATE_LABELS: Record<ResumeTemplateId, string> = {
  classic: '经典',
  modern: '现代',
  minimal: '简洁',
}

const templateId = ref<ResumeTemplateId>('classic')
const previewLoading = ref(false)
const exporting = ref(false)
const previewUrl = ref<string | null>(null)
/** 记录当前预览对应的 (task, revision, template)，任一变化即视为失效。 */
const previewKey = ref('')
const previewReceipt = ref<string | null>(null)
const previewPreflight = ref<ExportPreflight | null>(null)
let previewRequestSequence = 0
const artifacts = ref<ExportArtifact[]>([])
const artifactsLoading = ref(false)
const artifactsLoaded = ref(false)
const artifactsLoadError = ref<string | null>(null)
const downloadingId = ref<number | null>(null)

type OperationFailure = {
  operation: 'preview' | 'export' | 'download' | 'delete'
  message: string
  artifact?: ExportArtifact
}

const operationFailure = ref<OperationFailure | null>(null)
const exportSuccess = ref<ExportArtifact | null>(null)

// 只有最近一次保存成功后，才允许用服务端内容生成 Preview / Export；
// dirty / saving / failed / conflict 时前端草稿一律不得进入渲染链路。
const canOperate = computed(
  () => props.status === 'saved' && props.revision !== null && props.revision > 0,
)
const currentPreviewKey = computed(
  () => `${props.optimizationTaskId}:${props.revision}:${templateId.value}`,
)
const canExport = computed(
  () =>
    canOperate.value &&
    previewKey.value === currentPreviewKey.value &&
    previewReceipt.value !== null &&
    previewPreflight.value !== null &&
    !previewPreflight.value.missingContact &&
    !previewPreflight.value.overflowDetected &&
    !previewPreflight.value.orphanFinalPage &&
    !previewPreflight.value.readabilityTooSmall &&
    !previewPreflight.value.needsReview,
)

const blockingPreflightMessages = computed(() => {
  const result = previewPreflight.value
  if (!result) return []
  const messages: string[] = []
  if (result.missingContact) messages.push('缺少可用联系方式，需要补充电话或邮箱')
  if (result.overflowDetected) messages.push('检测到文字超出页面边界，需要调整内容或编辑器字段')
  if (result.orphanFinalPage) messages.push('末页内容过少，需要调整内容分页')
  if (result.readabilityTooSmall) messages.push('部分字号低于可读下限，需要调整内容')
  if (result.needsReview) messages.push('简历内容仍需确认，完成确认后才能导出')
  return messages
})

const advisoryPreflightMessages = computed(() => {
  const result = previewPreflight.value
  if (!result || !result.pageLimitExceeded) return []
  return ['当前 PDF 超过建议的 2 页，可以导出，但建议检查内容取舍']
})

const allPreflightMessages = computed(() => [
  ...blockingPreflightMessages.value,
  ...advisoryPreflightMessages.value,
])

const preflightStatusLabel = computed(() => {
  if (!previewPreflight.value) return '尚未检查'
  if (blockingPreflightMessages.value.length) return '需要处理后才能导出'
  if (advisoryPreflightMessages.value.length) return '可以导出，建议检查页数'
  return '可以导出'
})

const preflightStatusClass = computed(() => {
  if (!previewPreflight.value) return 'is-pending'
  if (blockingPreflightMessages.value.length) return 'is-blocked'
  if (advisoryPreflightMessages.value.length) return 'is-advisory'
  return 'is-ready'
})

const preflightChecks = computed(() => {
  const result = previewPreflight.value
  if (!result) return []
  return [
    {
      label: '联系方式',
      detail: result.missingContact ? '缺少可用联系方式' : '已检测到可用联系方式',
      state: result.missingContact ? 'warning' : 'ready',
    },
    {
      label: '页面边界',
      detail: result.overflowDetected ? '检测到文字越界' : '未检测到文字越界',
      state: result.overflowDetected ? 'warning' : 'ready',
    },
    {
      label: '页面结构',
      detail: result.orphanFinalPage ? '末页内容过少' : '页面结构正常',
      state: result.orphanFinalPage ? 'warning' : 'ready',
    },
    {
      label: '文字可读性',
      detail: result.readabilityTooSmall ? '部分字号低于可读下限' : '字号检查通过',
      state: result.readabilityTooSmall ? 'warning' : 'ready',
    },
  ] as const
})

const previewStateLabel = computed(() => {
  if (!canOperate.value) return '等待保存'
  if (previewLoading.value) return '正在生成'
  if (operationFailure.value?.operation === 'preview') return '生成失败'
  if (previewUrl.value) return '已生成'
  return '尚未生成'
})

const operateHint = computed(() => {
  if (props.status === 'saved' && (props.revision === null || props.revision <= 0)) {
    return '请先完成一次保存，再生成预览'
  }
  switch (props.status) {
    case 'dirty':
    case 'saving':
      return '正在保存，保存完成后可生成预览'
    case 'failed':
      return '保存失败，请先重试保存'
    case 'conflict':
      return '存在保存冲突，请先处理冲突'
    default:
      return ''
  }
})

const revokePreviewUrl = () => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

const invalidatePreview = () => {
  previewRequestSequence += 1
  exportSuccess.value = null
  revokePreviewUrl()
  previewKey.value = ''
  previewReceipt.value = null
  previewPreflight.value = null
  if (
    operationFailure.value?.operation === 'preview' ||
    operationFailure.value?.operation === 'export'
  ) {
    operationFailure.value = null
  }
}

type ApiErrorLike = Error & { code?: number }

const isStaleError = (error: unknown): error is ApiErrorLike =>
  typeof error === 'object' &&
  error !== null &&
  'message' in error &&
  (error as ApiErrorLike).code === 409

const handleStale = (message: string) => {
  invalidatePreview()
  ElMessage.warning(message)
  emit('stale')
}

const isCurrentRequest = (
  requestSequence: number,
  taskAtRequest: number,
  revisionAtRequest: number,
  templateAtRequest: ResumeTemplateId,
) =>
  requestSequence === previewRequestSequence &&
  props.status === 'saved' &&
  props.optimizationTaskId === taskAtRequest &&
  props.revision === revisionAtRequest &&
  templateId.value === templateAtRequest

const handlePreview = async () => {
  if (!canOperate.value || previewLoading.value || props.revision === null) return
  operationFailure.value = null
  previewLoading.value = true
  const taskAtRequest = props.optimizationTaskId
  const revisionAtRequest = props.revision
  const templateAtRequest = templateId.value
  const requestSequence = ++previewRequestSequence
  try {
    const preview = await previewWorkspacePdf(taskAtRequest, templateAtRequest, revisionAtRequest)
    if (
      !isCurrentRequest(requestSequence, taskAtRequest, revisionAtRequest, templateAtRequest) ||
      preview.contentRevision !== revisionAtRequest
    ) {
      return
    }
    revokePreviewUrl()
    previewUrl.value = URL.createObjectURL(new Blob([preview.blob], { type: 'application/pdf' }))
    previewKey.value = `${taskAtRequest}:${revisionAtRequest}:${templateAtRequest}`
    previewReceipt.value = preview.previewReceipt
    previewPreflight.value = preview.preflight
  } catch (error) {
    if (!isCurrentRequest(requestSequence, taskAtRequest, revisionAtRequest, templateAtRequest)) {
      return
    }
    const message = error instanceof Error ? error.message : '预览生成失败，请稍后重试'
    if (isStaleError(error)) {
      handleStale(message)
    } else {
      operationFailure.value = { operation: 'preview', message }
      ElMessage.error(message)
    }
  } finally {
    previewLoading.value = false
  }
}

const triggerBrowserDownload = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

const refreshArtifacts = async () => {
  artifactsLoading.value = true
  artifactsLoadError.value = null
  try {
    artifacts.value = await listWorkspaceArtifacts(props.optimizationTaskId)
    artifactsLoaded.value = true
  } catch (error) {
    artifactsLoadError.value = error instanceof Error ? error.message : '暂时无法读取导出记录'
  } finally {
    artifactsLoading.value = false
  }
}

const handleHistoryToggle = (event: Event) => {
  const open = (event.target as HTMLDetailsElement).open
  if (open && !artifactsLoaded.value && !artifactsLoading.value) {
    void refreshArtifacts()
  }
}

const handleExport = async () => {
  if (
    !canExport.value ||
    exporting.value ||
    props.revision === null ||
    previewReceipt.value === null
  )
    return
  operationFailure.value = null
  exportSuccess.value = null
  exporting.value = true
  const taskAtRequest = props.optimizationTaskId
  const revisionAtRequest = props.revision
  const templateAtRequest = templateId.value
  const receiptAtRequest = previewReceipt.value
  const requestSequence = previewRequestSequence
  const requestIsCurrent = () =>
    isCurrentRequest(requestSequence, taskAtRequest, revisionAtRequest, templateAtRequest) &&
    previewKey.value === `${taskAtRequest}:${revisionAtRequest}:${templateAtRequest}` &&
    previewReceipt.value === receiptAtRequest
  let exportedArtifact: ExportArtifact | null = null
  try {
    exportedArtifact = await exportWorkspacePdf(taskAtRequest, {
      templateId: templateAtRequest,
      expectedRevision: revisionAtRequest,
      previewReceipt: receiptAtRequest,
    })
    await refreshArtifacts()
    // 导出请求在本地编辑、任务、模板或预览凭证变化后完成时，绝不自动下载旧版本。
    if (!requestIsCurrent()) return
    const blob = await downloadArtifactPdf(exportedArtifact.id)
    if (!requestIsCurrent()) return
    triggerBrowserDownload(blob, exportedArtifact.fileName)
    exportSuccess.value = exportedArtifact
    ElMessage.success('PDF 导出成功')
  } catch (error) {
    if (!requestIsCurrent()) return
    const message = error instanceof Error ? error.message : '导出失败，请稍后重试'
    if (!exportedArtifact && isStaleError(error)) {
      handleStale(message)
    } else {
      operationFailure.value = exportedArtifact
        ? { operation: 'download', message, artifact: exportedArtifact }
        : { operation: 'export', message }
      ElMessage.error(message)
    }
  } finally {
    exporting.value = false
  }
}

const handleDownloadArtifact = async (artifact: ExportArtifact) => {
  if (downloadingId.value !== null) return
  operationFailure.value = null
  downloadingId.value = artifact.id
  try {
    const blob = await downloadArtifactPdf(artifact.id)
    triggerBrowserDownload(blob, artifact.fileName)
    exportSuccess.value = artifact
  } catch (error) {
    const message = error instanceof Error ? error.message : '下载失败，请稍后重试'
    operationFailure.value = { operation: 'download', message, artifact }
    ElMessage.error(message)
  } finally {
    downloadingId.value = null
  }
}

const handleDeleteArtifact = async (artifact: ExportArtifact) => {
  try {
    await ElMessageBox.confirm('删除后该 PDF 不可恢复，是否继续？', '删除导出文件', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  operationFailure.value = null
  try {
    await deleteWorkspaceArtifact(artifact.id)
    if (exportSuccess.value?.id === artifact.id) exportSuccess.value = null
    await refreshArtifacts()
    ElMessage.success('已删除导出文件')
  } catch (error) {
    await refreshArtifacts()
    const message = error instanceof Error ? error.message : '删除失败，请稍后重试'
    operationFailure.value = { operation: 'delete', message, artifact }
    ElMessage.error(message)
  }
}

const operationFailureTitle = computed(() => {
  switch (operationFailure.value?.operation) {
    case 'preview':
      return 'PDF 预览未生成'
    case 'export':
      return 'PDF 导出失败'
    case 'download':
      return 'PDF 下载失败'
    case 'delete':
      return '删除导出文件失败'
    default:
      return '操作失败'
  }
})

const operationFailureActionText = computed(() => {
  switch (operationFailure.value?.operation) {
    case 'preview':
      return '重新预览'
    case 'export':
      return '重新导出'
    case 'download':
      return '重新下载'
    case 'delete':
      return '重试删除'
    default:
      return '重试'
  }
})

const retryFailedOperation = () => {
  const failure = operationFailure.value
  if (!failure) return
  if (failure.operation === 'preview') void handlePreview()
  else if (failure.operation === 'export') void handleExport()
  else if (failure.operation === 'download' && failure.artifact)
    void handleDownloadArtifact(failure.artifact)
  else if (failure.operation === 'delete' && failure.artifact)
    void handleDeleteArtifact(failure.artifact)
}

const formatCreatedAt = (value: string) => value.replace('T', ' ').slice(0, 16)

// 状态、revision、模板或任务变化时立即使旧预览及仍在途的响应失效。
watch(
  () => [props.status, props.revision, templateId.value, props.optimizationTaskId],
  () => {
    if (props.status !== 'saved' || previewKey.value !== currentPreviewKey.value) {
      invalidatePreview()
    }
  },
)

onMounted(() => {
  // 进入 Preview mode 即生成一次预览；操作栏仍保留“重新预览”供模板切换后使用。
  if (canOperate.value) void handlePreview()
})

onBeforeUnmount(() => {
  invalidatePreview()
})
</script>

<template>
  <div class="preview-export">
    <div class="preview-layout">
      <section
        class="preview-document"
        aria-label="PDF 文档预览"
        :aria-busy="previewLoading"
      >
        <header class="preview-document-toolbar">
          <div>
            <span class="preview-section-label">最终文档</span>
            <strong>PDF 预览</strong>
          </div>
          <a v-if="previewUrl" class="preview-open-link" :href="previewUrl" target="_blank" rel="noopener">
            在新窗口打开 PDF
          </a>
        </header>
        <div class="preview-document-canvas">
          <div v-if="previewLoading" class="preview-placeholder" role="status">
            <strong>正在生成预览…</strong>
            <span>使用最近一次成功保存的简历内容。</span>
          </div>
          <iframe
            v-else-if="previewUrl"
            :src="previewUrl"
            class="preview-frame"
            title="简历 PDF 预览"
          />
          <div v-else class="preview-placeholder">
            <strong>预览将在这里显示</strong>
            <span>生成预览后，可以在导出前检查最终文档。</span>
          </div>
        </div>
      </section>

      <aside class="preview-inspector" aria-label="导出检查器">
        <section class="preview-inspector-section preview-template-section">
          <span class="preview-section-label">模板</span>
          <h2>选择简历模板</h2>
          <el-radio-group v-model="templateId" size="small" aria-label="选择简历模板">
            <el-radio-button
              v-for="option in TEMPLATE_OPTIONS"
              :key="option.value"
              :value="option.value"
              :data-testid="`preview-template-${option.value}`"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <p>修改模板后需要重新生成预览。</p>
        </section>

        <section class="preview-inspector-section">
          <div class="preview-section-heading">
            <span class="preview-section-label">预览状态</span>
            <strong>{{ previewStateLabel }}</strong>
          </div>
          <p v-if="!canOperate" class="operate-hint">{{ operateHint }}</p>
          <p v-else-if="!previewPreflight && !previewLoading" class="preview-first-hint">
            先生成一次预览，系统会检查最终文档后再允许导出。
          </p>
          <div class="preview-state-row">
            <span>当前文档</span>
            <span>{{ previewPreflight ? `${previewPreflight.pageCount} 页` : '等待检查' }}</span>
          </div>
          <el-button
            size="small"
            :loading="previewLoading"
            :disabled="!canOperate"
            @click="handlePreview"
          >
            {{ previewUrl ? '重新预览' : '生成预览' }}
          </el-button>
        </section>

        <section v-if="previewPreflight" class="preview-inspector-section preflight-section">
          <div class="preview-section-heading">
            <span class="preview-section-label">导出前检查</span>
            <strong :class="preflightStatusClass">{{ preflightStatusLabel }}</strong>
          </div>
          <div class="preflight-summary">
            <strong>{{ previewPreflight.pageCount }} 页</strong>
            <span v-if="previewPreflight.pageLimitExceeded" class="is-advisory">超过建议的 2 页</span>
          </div>
          <ul class="preflight-check-list" role="list">
            <li v-for="check in preflightChecks" :key="check.label" :class="`is-${check.state}`">
              <span class="preflight-check-marker" aria-hidden="true" />
              <span><strong>{{ check.label }}</strong><small>{{ check.detail }}</small></span>
            </li>
          </ul>
          <p v-if="blockingPreflightMessages.length" class="preflight-blocked-copy">
            处理后才能导出：{{ blockingPreflightMessages[0] }}
          </p>
          <details v-if="allPreflightMessages.length" class="preflight-details">
            <summary>查看完整检查</summary>
            <ul>
              <li v-for="message in allPreflightMessages" :key="message">{{ message }}</li>
            </ul>
          </details>
        </section>

        <ErrorState
          v-if="operationFailure"
          compact
          :title="operationFailureTitle"
          :description="operationFailure.message"
          :action-text="operationFailureActionText"
          @action="retryFailedOperation"
        />

        <section class="preview-inspector-section preview-export-action">
          <div class="preview-section-heading">
            <span class="preview-section-label">导出</span>
            <strong v-if="exportSuccess" class="is-success">PDF 已生成</strong>
          </div>
          <p v-if="exportSuccess" class="export-success-copy">
            {{ exportSuccess.fileName }} · {{ exportSuccess.pageCount }} 页 · {{ Math.ceil(exportSuccess.fileSize / 1024) }} KB
          </p>
          <p v-else-if="!canExport" class="export-blocked-copy">
            {{ preflightStatusLabel === '尚未检查' ? '生成预览并完成导出前检查后可导出。' : '请先处理导出前检查中的阻断项。' }}
          </p>
          <el-button type="primary" :loading="exporting" :disabled="!canExport" @click="handleExport">
            导出 PDF
          </el-button>
        </section>

        <details class="export-history" @toggle="handleHistoryToggle">
          <summary>
            <span class="preview-section-label">导出记录</span>
            <span>最近导出</span>
          </summary>
          <div class="export-history-content">
            <p v-if="artifactsLoading && artifacts.length === 0" class="artifact-status">
              正在读取导出记录…
            </p>
            <ErrorState
              v-else-if="artifactsLoadError"
              compact
              title="导出记录加载失败"
              :description="artifactsLoadError"
              action-text="重新加载"
              @action="refreshArtifacts"
            />
            <p v-else-if="artifacts.length === 0" class="artifact-status">还没有导出记录。</p>
            <ul v-else class="artifact-list">
              <li v-for="artifact in artifacts" :key="artifact.id">
                <div class="artifact-info">
                  <span class="artifact-name">{{ artifact.fileName }}</span>
                  <span class="artifact-meta">
                    {{ TEMPLATE_LABELS[artifact.templateId] }} ·
                    {{ artifact.pageCount }} 页 · {{ Math.ceil(artifact.fileSize / 1024) }} KB ·
                    {{ artifact.status === 'DELETE_PENDING' ? '待删除' : '已就绪' }} ·
                    {{ formatCreatedAt(artifact.createdAt) }}
                  </span>
                </div>
                <div class="artifact-actions">
                  <el-button
                    size="small"
                    :aria-label="`下载 ${artifact.fileName}`"
                    :loading="downloadingId === artifact.id"
                    :disabled="artifact.status !== 'READY'"
                    @click="handleDownloadArtifact(artifact)"
                  >
                    下载
                  </el-button>
                  <el-button
                    size="small"
                    type="danger"
                    plain
                    :aria-label="`${artifact.status === 'DELETE_PENDING' ? '重试删除' : '删除'} ${artifact.fileName}`"
                    @click="handleDeleteArtifact(artifact)"
                  >
                    {{ artifact.status === 'DELETE_PENDING' ? '重试删除' : '删除' }}
                  </el-button>
                </div>
              </li>
            </ul>
          </div>
        </details>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.preview-export {
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 0;
  height: 100%;
  flex-direction: column;
}

.preview-layout {
  display: grid;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: minmax(0, 1fr) clamp(280px, 24vw, 360px);
  overflow: hidden;
  background: var(--app-stage);
}

.preview-document {
  display: grid;
  min-width: 0;
  min-height: 0;
  height: 100%;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  border-right: 1px solid var(--app-border-strong);
  background: var(--app-pdf-canvas);
}

.preview-document-toolbar {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-4);
  padding: 0 var(--app-space-5);
  border-bottom: 1px solid var(--app-border-strong);
  background: var(--app-surface-soft);
}

.preview-document-toolbar > div {
  display: flex;
  align-items: baseline;
  gap: var(--app-space-3);
}

.preview-document-toolbar strong {
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
}

.preview-open-link {
  color: var(--app-primary-active);
  font-size: var(--app-font-size-xs);
  font-weight: 700;
  white-space: nowrap;
}

.preview-open-link:hover,
.preview-open-link:focus-visible {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.preview-document-canvas {
  display: grid;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.preview-frame {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 0;
  border: 0;
  background: var(--app-surface);
}

.preview-placeholder {
  display: grid;
  min-height: 0;
  height: 100%;
  place-items: center;
  align-content: center;
  gap: var(--app-space-2);
  padding: var(--app-space-8);
  color: var(--app-text-secondary);
  text-align: center;
  background: var(--app-surface-soft);
}

.preview-placeholder strong {
  color: var(--app-text);
  font-size: var(--app-font-size-lg);
}

.preview-placeholder span {
  max-width: 42ch;
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.preview-inspector {
  display: flex;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  flex-direction: column;
  background: var(--app-surface-soft);
  scrollbar-color: var(--app-scroll-thumb) transparent;
  scrollbar-width: thin;
}

.preview-inspector-section {
  display: grid;
  gap: var(--app-space-3);
  border-bottom: 1px solid var(--app-border);
  padding: var(--app-space-5) var(--app-space-5);
}

.preview-section-label {
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.preview-inspector-section h2 {
  margin: 0;
  color: var(--app-text);
  font-size: var(--app-font-size-lg);
  line-height: var(--app-line-height-tight);
}

.preview-template-section p,
.operate-hint,
.preview-first-hint,
.preview-note,
.artifact-status,
.export-blocked-copy,
.export-success-copy {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
}

.preview-template-section p {
  color: var(--app-text-muted);
}

.preview-template-section :deep(.el-radio-group) {
  display: flex;
  flex-wrap: wrap;
}

.preview-template-section :deep(.el-radio-button__inner) {
  min-height: 34px;
  padding: 0 var(--app-space-3);
  line-height: 34px;
}

.preview-section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--app-space-3);
}

.preview-section-heading strong {
  color: var(--app-text);
  font-size: var(--app-font-size-xs);
}

.preview-section-heading strong.is-ready,
.preview-section-heading strong.is-success {
  color: var(--app-success);
}

.preview-section-heading strong.is-advisory {
  color: var(--app-warning);
}

.preview-section-heading strong.is-blocked {
  color: var(--app-danger);
}

.preview-state-row {
  display: flex;
  justify-content: space-between;
  gap: var(--app-space-3);
  border-top: 1px solid var(--app-border-soft);
  padding-top: var(--app-space-3);
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
}

.preview-state-row span:last-child {
  color: var(--app-text);
  font-weight: 700;
}

.preflight-summary {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--app-space-3);
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
}

.preflight-summary .is-advisory {
  color: var(--app-warning);
  font-size: var(--app-font-size-xs);
}

.preflight-check-list {
  display: grid;
  gap: var(--app-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.preflight-check-list li {
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr);
  gap: var(--app-space-2);
  align-items: start;
  color: var(--app-text);
  font-size: var(--app-font-size-xs);
}

.preflight-check-marker {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--app-success);
}

.preflight-check-list li.is-warning .preflight-check-marker {
  background: var(--app-danger);
}

.preflight-check-list li span:last-child {
  display: grid;
  gap: 2px;
}

.preflight-check-list small {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
}

.preflight-check-list li.is-warning small {
  color: var(--app-danger);
}

.preflight-blocked-copy {
  margin: 0;
  color: var(--app-danger);
  font-size: var(--app-font-size-xs);
  line-height: var(--app-line-height-body);
}

.preflight-details {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-xs);
}

.preflight-details summary {
  width: fit-content;
  color: var(--app-primary);
  font-weight: 700;
  cursor: pointer;
}

.preflight-details ul {
  display: grid;
  gap: var(--app-space-1);
  margin: var(--app-space-2) 0 0;
  padding-left: var(--app-space-5);
  line-height: var(--app-line-height-body);
}

.preview-export-action {
  background: color-mix(in srgb, var(--app-surface-soft) 70%, var(--app-bg-soft));
}

.preview-export-action :deep(.el-button) {
  width: 100%;
  min-height: 40px;
}

.export-blocked-copy {
  color: var(--app-warning);
}

.export-success-copy {
  color: var(--app-text);
}

.preview-export > .preview-inspector > :deep(.ui-empty-state),
.preview-inspector > :deep(.ui-error-state) {
  margin: var(--app-space-4) var(--app-space-5);
}

.export-history {
  border-top: 1px solid var(--app-border-strong);
}

.export-history summary {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--app-space-3);
  padding: var(--app-space-4) var(--app-space-5);
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
  font-weight: 700;
  cursor: pointer;
}

.export-history summary .preview-section-label {
  color: var(--app-text-muted);
  font-size: 10px;
}

.export-history-content {
  display: grid;
  gap: var(--app-space-3);
  padding: 0 var(--app-space-5) var(--app-space-5);
}

.artifact-list {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}

.artifact-list li {
  display: grid;
  gap: var(--app-space-3);
  border-top: 1px solid var(--app-border);
  padding: var(--app-space-3) 0;
}

.artifact-info {
  display: grid;
  gap: var(--app-space-1);
  min-width: 0;
}

.artifact-name {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: var(--app-font-size-xs);
  font-weight: 700;
}

.artifact-meta {
  color: var(--app-text-secondary);
  font-size: 11px;
  line-height: var(--app-line-height-body);
}

.artifact-actions {
  display: flex;
  gap: var(--app-space-2);
}

@media (max-width: 1119px) {
  .preview-export {
    height: auto;
    min-height: 0;
  }

  .preview-layout {
    display: flex;
    overflow: visible;
    flex-direction: column;
  }

  .preview-document {
    height: min(72dvh, 720px);
    min-height: 520px;
    border-right: 0;
    border-bottom: 1px solid var(--app-border-strong);
  }

  .preview-inspector {
    overflow: visible;
  }

  .preview-inspector-section {
    padding-right: var(--app-content-gutter-narrow);
    padding-left: var(--app-content-gutter-narrow);
  }

  .export-history summary,
  .export-history-content {
    padding-right: var(--app-content-gutter-narrow);
    padding-left: var(--app-content-gutter-narrow);
  }
}

@media (max-width: 640px) {
  .preview-document {
    height: min(64dvh, 560px);
    min-height: 360px;
  }

  .preview-inspector-section {
    padding-top: var(--app-space-4);
    padding-bottom: var(--app-space-4);
  }

  .preview-template-section :deep(.el-radio-button__inner) {
    min-height: 40px;
    line-height: 40px;
  }

  .artifact-actions {
    flex-wrap: wrap;
  }
}
</style>
