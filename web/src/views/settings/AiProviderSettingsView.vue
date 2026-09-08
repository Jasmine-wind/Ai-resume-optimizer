<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ErrorState from '@/components/common/ErrorState.vue'
import SettingsLayout from '@/components/settings/SettingsLayout.vue'
import { presentAiFailure } from '@/utils/aiFailurePresentation'
import { resolveSafeRedirect } from '@/utils/safeRedirect'
import SkeletonBlock from '@/components/common/SkeletonBlock.vue'
import {
  deleteAiProvider,
  disableAiProvider,
  enableAiProvider,
  getAiProviderSettings,
  saveAiProviderSettings,
  testAiProvider,
  type AiProviderCredential,
  resolveAiProviderConfigurationState,
  type AiProviderConfigurationState,
} from '@/api/ai-provider'

const route = useRoute()
const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
const testing = ref(false)
const actionLoading = ref(false)
const settings = ref<AiProviderCredential | null>(null)
const testResult = ref<{ success: boolean; message: string; failureCode?: string } | null>(null)
const saveError = ref<string | null>(null)
const actionError = ref<string | null>(null)
const savedNeedsEnable = ref(false)

const form = reactive({
  baseUrl: '',
  apiKey: '',
  model: '',
})

const configured = computed(() => Boolean(settings.value?.configured))
const active = computed(() => settings.value?.status === 'ACTIVE')
// Capability fields are part of the backend contract. Missing storage capability fails closed.
const storageAvailable = computed(() => settings.value?.credentialStorageAvailable ?? false)
const configurationState = computed<AiProviderConfigurationState>(() =>
  settings.value
    ? resolveAiProviderConfigurationState(settings.value)
    : 'UNCONFIGURED',
)
const canTest = computed(() => Boolean(form.baseUrl.trim() && form.apiKey.trim() && form.model.trim()))
const canSubmit = computed(() => Boolean(storageAvailable.value && canTest.value))
const currentAiDescription = computed(() => {
  switch (configurationState.value) {
    case 'ACTIVE':
      return '你的 API 已启用，新创建的 AI 任务会使用这份配置。'
    case 'SAVED_DISABLED':
      return '你的 API 已安全保存，但尚未启用。启用后，新任务才会使用 AI。'
    case 'UNCONFIGURED':
      return '配置并启用自己的 API 后，即可使用岗位分析和 AI 优化。'
    default:
      return '配置并启用自己的 API 后，即可使用岗位分析和 AI 优化。'
  }
})
const statusLabel = computed(() => {
  switch (configurationState.value) {
    case 'ACTIVE':
      return '你的 API 已启用'
    case 'SAVED_DISABLED':
      return 'API 已保存，尚未启用'
    case 'UNCONFIGURED':
      return 'AI 尚未配置'
    default:
      return 'AI 尚未配置'
  }
})
const nextStepDescription = '启用后，新任务才能使用你的 API。'
const disableSuccessMessage = '已停用你的 API；新的 AI 任务暂时不可用。'
const deleteConfirmationMessage = '删除后，你保存的 API 密钥将被移除。在重新配置并启用 AI 前，新的 AI 任务将无法使用。是否确认删除？'
const securityDescription = '停用或删除 API 后，新任务无法使用 AI。'
const deleteDangerDescription = '删除后新任务无法使用 AI；'
const redirectPath = computed(() => resolveSafeRedirect(route.query.redirect))
const hasRedirect = computed(() => typeof route.query.redirect === 'string')

const copySettingsToForm = (value: AiProviderCredential) => {
  form.baseUrl = value.baseUrl || ''
  form.model = value.model || ''
  // The API key intentionally never comes from the API response.
  form.apiKey = ''
}

const load = async () => {
  loading.value = true
  loadFailed.value = false
  try {
    settings.value = await getAiProviderSettings()
    savedNeedsEnable.value = false
    if (settings.value) copySettingsToForm(settings.value)
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

const input = () => ({
  baseUrl: form.baseUrl.trim(),
  apiKey: form.apiKey,
  model: form.model.trim(),
})

const handleTest = async () => {
  if (!canTest.value || testing.value) return
  testing.value = true
  testResult.value = null
  try {
    const result = await testAiProvider(input())
    const message = result.message || '连接测试没有通过'
    testResult.value = {
      success: result.success,
      failureCode: result.failureCode,
      message: result.success
        ? message
        : presentAiFailure(result.failureCode, message, 'settings'),
    }
    if (result.success) ElMessage.success('连接测试成功')
    else ElMessage.error(testResult.value?.message ?? message)
  } catch (error) {
    testResult.value = {
      success: false,
      message: error instanceof Error ? error.message : '连接测试失败',
    }
    ElMessage.error(testResult.value?.message ?? '连接测试失败')
  } finally {
    testing.value = false
  }
}

const handleSave = async () => {
  if (!canSubmit.value || saving.value) return
  saving.value = true
  saveError.value = null
  try {
    settings.value = await saveAiProviderSettings(input())
    savedNeedsEnable.value = true
    copySettingsToForm(settings.value)
    testResult.value = null
    ElMessage.success('配置已保存；为保护账号安全，当前仍处于停用状态，需要手动启用')
  } catch (error) {
    saveError.value = !storageAvailable.value || (error instanceof Error && error.message.includes('AI 配置不完整'))
      ? '当前环境暂时无法安全保存 API 密钥，请检查服务端密钥存储配置。'
      : error instanceof Error ? error.message : '保存配置失败'
    ElMessage.error(saveError.value)
  } finally {
    saving.value = false
  }
}

const handleToggle = async () => {
  if (!configured.value || actionLoading.value) return
  const wasActive = active.value
  actionError.value = null
  actionLoading.value = true
  try {
    settings.value = wasActive ? await disableAiProvider() : await enableAiProvider()
    savedNeedsEnable.value = false
    copySettingsToForm(settings.value)
    ElMessage.success(wasActive ? disableSuccessMessage : '已启用你的 API 密钥')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '更新状态失败'
    ElMessage.error(actionError.value)
  } finally {
    actionLoading.value = false
  }
}

const handleDelete = async () => {
  if (!configured.value || actionLoading.value) return
  try {
    await ElMessageBox.confirm(
      deleteConfirmationMessage,
      '删除你的 API 密钥',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionError.value = null
  actionLoading.value = true
  try {
    await deleteAiProvider()
    settings.value = await getAiProviderSettings()
    savedNeedsEnable.value = false
    copySettingsToForm(settings.value)
    ElMessage.success('已删除你的 API 密钥')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '删除失败'
    ElMessage.error(actionError.value)
  } finally {
    actionLoading.value = false
    form.apiKey = ''
  }
}

watch(
  () => [form.baseUrl, form.apiKey, form.model],
  () => {
    testResult.value = null
    saveError.value = null
  },
)

onMounted(load)
</script>

<template>
  <SettingsLayout current="ai-provider">
    <section class="settings-page">

    <SkeletonBlock v-if="loading" title :rows="8" />

    <ErrorState
      v-else-if="loadFailed"
      title="AI 设置加载失败"
      description="暂时无法读取你的配置状态，已保存的配置不受影响。"
      action-text="重新加载"
      @action="load"
    />

    <template v-else>
      <section class="settings-content-heading">
        <p class="settings-section-label">AI 配置</p>
        <h2>AI 设置</h2>
        <p>开始岗位分析前，请先配置并启用你自己的 API 密钥。</p>
        <RouterLink v-if="hasRedirect" class="settings-return-link" :to="redirectPath">返回开始优化</RouterLink>
      </section>

      <section class="settings-current-ai" aria-labelledby="current-ai-title">
        <div>
          <p class="settings-section-label">当前状态</p>
          <h2 id="current-ai-title">当前状态</h2>
          <p>{{ currentAiDescription }}</p>
        </div>
        <div class="settings-current-ai-status" :class="{ 'is-active': configurationState === 'ACTIVE', 'is-saved-disabled': configurationState === 'SAVED_DISABLED', 'is-unconfigured': configurationState === 'UNCONFIGURED' }">
          <span class="settings-status-dot" aria-hidden="true" />
          <strong>{{ statusLabel }}</strong>
        </div>
      </section>

      <div v-if="savedNeedsEnable && configured && storageAvailable && !active" class="settings-next-step" role="status">
        <strong>配置已保存，尚未启用</strong>
        <span>{{ nextStepDescription }}</span>
        <el-button type="primary" :loading="actionLoading" @click="handleToggle">启用</el-button>
      </div>

      <section class="settings-configuration" aria-labelledby="custom-ai-title">
        <header class="settings-section-header">
          <p class="settings-section-label">连接设置</p>
          <h2 id="custom-ai-title">使用自己的 API</h2>
          <p>配置并启用兼容 OpenAI 接口的 API 后，即可使用岗位分析和 AI 优化；密钥不会在页面回显。</p>
        </header>

        <p v-if="configured" class="settings-current-key">
          已保存密钥：<strong>{{ settings?.maskedApiKey || '已配置' }}</strong>
          <span> · 替换后会自动停用，需要再次启用。</span>
        </p>

        <p v-if="!storageAvailable" class="settings-storage-note" role="status">
          当前部署尚未启用 API 密钥保存。连接测试仍可使用；如需保存自己的 API，请先完成服务端密钥存储配置。
        </p>

        <el-form label-position="top" class="settings-form" @submit.prevent="handleSave">
          <el-form-item label="连接地址" required>
            <el-input
              v-model="form.baseUrl"
              placeholder="https://api.example.com/v1"
              autocomplete="url"
            />
            <small>必须使用 HTTPS 和标准 443 端口。</small>
          </el-form-item>

          <el-form-item label="API 密钥" required>
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="输入后用于测试或保存；保存后不会再次显示"
            />
          </el-form-item>

          <el-form-item label="模型" required>
            <el-input v-model="form.model" placeholder="例如 gpt-4o-mini" autocomplete="off" />
          </el-form-item>

          <div class="settings-actions">
            <el-button :loading="testing" :disabled="!canTest" @click="handleTest">测试连接</el-button>
            <el-button type="primary" native-type="submit" :loading="saving" :disabled="!canSubmit">
              {{ configured ? '保存并替换' : '保存配置' }}
            </el-button>
          </div>
        </el-form>

        <section v-if="testResult" class="settings-test-result" :class="testResult.success ? 'is-success' : 'is-error'" :role="testResult.success ? 'status' : 'alert'">
          <strong>{{ testResult.success ? '连接测试成功' : '连接测试失败' }}</strong>
          <p>{{ testResult.message }}</p>
        </section>
        <section v-if="saveError" class="settings-test-result is-error" role="alert">
          <strong>保存配置失败</strong>
          <p>{{ saveError }}</p>
        </section>
      </section>

      <section v-if="configured && storageAvailable" class="settings-status-actions" aria-labelledby="status-actions-title">
        <div>
          <p class="settings-section-label">配置状态</p>
          <h2 id="status-actions-title">{{ configurationState === 'ACTIVE' ? '你的 API 已启用' : '你的 API 已保存，尚未启用' }}</h2>
          <p>{{ configurationState === 'ACTIVE' ? '新岗位分析会使用已保存的连接。' : '启用后，新岗位分析才能使用已保存的连接。' }}</p>
        </div>
        <el-button :type="configurationState === 'ACTIVE' ? 'default' : 'primary'" :loading="actionLoading" @click="handleToggle">
          {{ configurationState === 'ACTIVE' ? '停用' : '启用' }}
        </el-button>
      </section>

      <section v-if="actionError" class="settings-action-error" role="alert">
        <strong>状态更新失败</strong>
        <p>{{ actionError }}</p>
      </section>

      <section class="settings-security" aria-labelledby="security-title">
        <p class="settings-section-label">安全说明</p>
        <h2 id="security-title">密钥只用于服务端连接</h2>
        <p>保存的密钥只显示为掩码，不会再次回显，也不会进入浏览器日志或页面存储。</p>
        <p>{{ securityDescription }}历史任务不会被改写。</p>
      </section>

      <section v-if="configured" class="settings-danger-zone">
        <div>
          <p class="settings-section-label">危险操作</p>
          <h2>删除密钥</h2>
          <p>{{ deleteDangerDescription }}已保存的密钥无法恢复。</p>
        </div>
        <el-button type="danger" plain :loading="actionLoading" @click="handleDelete">删除</el-button>
      </section>
    </template>
    </section>
  </SettingsLayout>
</template>

<style scoped>
.settings-page {
  display: grid;
  gap: var(--app-section-spacing);
}

.settings-content-heading,
.settings-current-ai,
.settings-configuration,
.settings-status-actions,
.settings-security,
.settings-danger-zone {
  min-width: 0;
  border-top: 1px solid var(--app-border-strong);
  padding: var(--app-space-6) 0;
}

.settings-current-ai {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-8);
}

.settings-current-ai > div:first-child,
.settings-status-actions > div,
.settings-danger-zone > div {
  display: grid;
  gap: var(--app-space-2);
}

.settings-content-heading h2,
.settings-current-ai h2,
.settings-section-header h2,
.settings-status-actions h2,
.settings-security h2,
.settings-danger-zone h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 20px;
  line-height: var(--app-line-height-tight);
}

.settings-content-heading > p:last-child,
.settings-current-ai p,
.settings-section-header p,
.settings-status-actions p,
.settings-security p,
.settings-danger-zone p,
.settings-current-key,
.settings-form small,
.settings-test-result p,
.settings-action-error p,
.settings-next-step span {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.settings-section-label {
  margin: 0;
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.settings-current-ai-status {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--app-space-2);
  color: var(--app-text);
  font-size: 15px;
}

.settings-status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--app-accent);
}

.settings-current-ai-status.is-active .settings-status-dot {
  background: var(--app-success);
}

.settings-current-ai-status.is-saved-disabled .settings-status-dot,
.settings-current-ai-status.is-unconfigured .settings-status-dot {
  background: var(--app-warning);
}

.settings-content-heading {
  display: grid;
  gap: var(--app-space-2);
  border-bottom: 1px solid var(--app-border-strong);
  padding-bottom: var(--app-space-5);
}

.settings-content-heading h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 22px;
}

.settings-storage-note {
  max-width: 680px;
  margin: 0 0 var(--app-space-5);
  border-left: 2px solid var(--app-warning);
  padding-left: var(--app-space-3);
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-sm);
  line-height: var(--app-line-height-body);
}

.settings-next-step {
  display: grid;
  grid-template-columns: minmax(0, auto) minmax(0, 1fr) auto;
  gap: var(--app-space-3) var(--app-space-4);
  align-items: center;
  border-top: 1px solid var(--app-primary-subtle);
  border-bottom: 1px solid var(--app-primary-subtle);
  padding: var(--app-space-4) 0;
  color: var(--app-text);
}

.settings-next-step strong {
  font-size: var(--app-font-size-sm);
}

.settings-next-step .el-button {
  grid-column: 3;
}

.settings-configuration {
  padding-bottom: var(--app-space-8);
}

.settings-section-header {
  display: grid;
  gap: var(--app-space-2);
  margin-bottom: var(--app-space-6);
}

.settings-form {
  max-width: 680px;
}

.settings-form small {
  display: block;
  margin-top: var(--app-space-1);
}

.settings-current-key {
  margin-bottom: var(--app-space-5);
}

.settings-current-key strong {
  color: var(--app-text);
  font-family: var(--app-font-mono);
  font-weight: 700;
}

.settings-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--app-space-2);
  margin-top: var(--app-space-2);
}

.settings-test-result {
  display: grid;
  gap: var(--app-space-1);
  max-width: 680px;
  margin-top: var(--app-space-5);
  border-top: 1px solid var(--app-border);
  padding-top: var(--app-space-4);
}

.settings-test-result strong,
.settings-action-error strong {
  color: var(--app-text);
  font-size: var(--app-font-size-sm);
}

.settings-test-result.is-success strong {
  color: var(--app-success);
}

.settings-test-result.is-error strong,
.settings-action-error strong {
  color: var(--app-danger);
}

.settings-status-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-6);
}

.settings-action-error {
  display: grid;
  gap: var(--app-space-1);
  border-top-color: var(--app-danger);
}

.settings-security {
  display: grid;
  gap: var(--app-space-2);
  max-width: 760px;
}

.settings-danger-zone {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--app-space-6);
  border-top-color: var(--app-danger);
}

.settings-danger-zone .settings-section-label,
.settings-danger-zone h2 {
  color: var(--app-danger);
}

@media (max-width: 640px) {
  .settings-current-ai,
  .settings-status-actions,
  .settings-danger-zone {
    align-items: flex-start;
    flex-direction: column;
  }

  .settings-current-ai-status {
    padding-top: var(--app-space-1);
  }

  .settings-next-step {
    grid-template-columns: 1fr;
  }

  .settings-next-step .el-button {
    grid-column: 1;
    width: 100%;
  }

  .settings-actions,
  .settings-actions .el-button,
  .settings-status-actions .el-button,
  .settings-danger-zone .el-button {
    width: 100%;
  }

  .settings-actions {
    display: grid;
  }
}
</style>
