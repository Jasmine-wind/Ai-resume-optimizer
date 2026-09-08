// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiProviderSettingsView from '@/views/settings/AiProviderSettingsView.vue'
import {
  deleteAiProvider,
  disableAiProvider,
  enableAiProvider,
  getAiProviderSettings,
  testAiProvider,
} from '@/api/ai-provider'
import type { AiProviderCredential } from '@/api/ai-provider'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

const { messageError, messageSuccess, confirm } = vi.hoisted(() => ({
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  confirm: vi.fn(),
}))

const elementPlusStubs = vi.hoisted(() => ({
  ElButton: {
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  ElForm: { emits: ['submit'], template: '<form @submit="$emit(\'submit\', $event)"><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: {
    props: ['modelValue', 'type', 'placeholder'],
    emits: ['update:modelValue'],
    template: '<input :type="type || \'text\'" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: messageError, success: messageSuccess },
  ElMessageBox: { confirm },
  ...elementPlusStubs,
}))
vi.mock('element-plus/es', () => ({
  ElMessage: { error: messageError, success: messageSuccess },
  ElMessageBox: { confirm },
  ...elementPlusStubs,
}))

vi.mock('@/api/ai-provider', () => ({
  deleteAiProvider: vi.fn(),
  disableAiProvider: vi.fn(),
  enableAiProvider: vi.fn(),
  getAiProviderSettings: vi.fn(),
  resolveAiProviderConfigurationState: (credential: AiProviderCredential) => {
    if (!credential.configured) return 'UNCONFIGURED'
    if (credential.status === 'ACTIVE' && credential.credentialStorageAvailable === true) return 'ACTIVE'
    return 'SAVED_DISABLED'
  },
  saveAiProviderSettings: vi.fn(),
  testAiProvider: vi.fn(),
}))

const getSettingsMock = vi.mocked(getAiProviderSettings)
const enableMock = vi.mocked(enableAiProvider)
const disableMock = vi.mocked(disableAiProvider)
const deleteMock = vi.mocked(deleteAiProvider)
const testMock = vi.mocked(testAiProvider)

const credential = (
  status: 'ACTIVE' | 'DISABLED',
  configured: boolean,
  credentialStorageAvailable = true,
): AiProviderCredential => ({
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.example.com/v1',
  model: 'gate-model',
  config: {},
  status,
  configured,
  apiKeyConfigured: configured,
  maskedApiKey: configured ? 'sk-***' : '',
  credentialStorageAvailable,
})

const mountLoaded = async (settings: AiProviderCredential) => {
  getSettingsMock.mockResolvedValue(settings)
  const wrapper = mount(AiProviderSettingsView, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

const button = (wrapper: Awaited<ReturnType<typeof mountLoaded>>, text: string) =>
  wrapper.findAll('button').find((item) => item.text().includes(text))!

describe('AiProviderSettingsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the three BYOK states without any system-provider concept', async () => {
    const unconfigured = await mountLoaded(credential('DISABLED', false))
    expect(unconfigured.text()).toContain('AI 尚未配置')
    expect(unconfigured.text()).not.toContain('系统 AI')
    unconfigured.unmount()

    const disabled = await mountLoaded(credential('DISABLED', true))
    expect(disabled.text()).toContain('API 已保存，尚未启用')
    disabled.unmount()

    const active = await mountLoaded(credential('ACTIVE', true))
    expect(active.text()).toContain('你的 API 已启用')
    expect(active.text()).toContain('新创建的 AI 任务会使用这份配置。')
  })

  it('presents BYOK as required connection setup rather than an optional advanced feature', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', false))

    expect(wrapper.text()).toContain('AI 配置')
    expect(wrapper.text()).toContain('连接设置')
    expect(wrapper.text()).toContain('开始岗位分析前，请先配置并启用你自己的 API 密钥。')
    expect(wrapper.text()).toContain('配置并启用兼容 OpenAI 接口的 API 后，即可使用岗位分析和 AI 优化；密钥不会在页面回显。')
    expect(wrapper.text()).not.toContain('可选配置')
    expect(wrapper.text()).not.toContain('高级设置')
    expect(wrapper.text()).not.toContain('高级能力')
  })

  it('disables BYOK without promising another provider', async () => {
    disableMock.mockResolvedValue(credential('DISABLED', true))
    const wrapper = await mountLoaded(credential('ACTIVE', true))

    await button(wrapper, '停用').trigger('click')
    await flushPromises()

    expect(disableMock).toHaveBeenCalledOnce()
    expect(messageSuccess).toHaveBeenCalledWith('已停用你的 API；新的 AI 任务暂时不可用。')
    expect(wrapper.text()).not.toContain('系统 AI')
  })

  it('enables a saved credential and keeps test input local', async () => {
    enableMock.mockResolvedValue(credential('ACTIVE', true))
    testMock.mockResolvedValue({ success: true, message: '连接正常' })
    const wrapper = await mountLoaded(credential('DISABLED', true))
    const inputs = wrapper.findAll('input')
    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')
    await button(wrapper, '测试连接').trigger('click')
    await flushPromises()

    expect(inputs[1]!.element.value).toBe('secret-key')
    await button(wrapper, '启用').trigger('click')
    await flushPromises()
    expect(enableMock).toHaveBeenCalledOnce()
    expect(messageSuccess).toHaveBeenCalledWith('已启用你的 API 密钥')
  })

  it('allows Test but fail-closed disables Save when storage is unavailable', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', false, false))
    const inputs = wrapper.findAll('input')
    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')

    expect(button(wrapper, '测试连接').element.disabled).toBe(false)
    expect(button(wrapper, '保存配置').element.disabled).toBe(true)
  })

  it('uses the exact delete recovery copy', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', true))
    confirm.mockResolvedValueOnce(true)
    deleteMock.mockResolvedValue(undefined)
    getSettingsMock.mockResolvedValue(credential('DISABLED', false))

    await button(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      '删除后，你保存的 API 密钥将被移除。在重新配置并启用 AI 前，新的 AI 任务将无法使用。是否确认删除？',
      '删除你的 API 密钥',
      expect.any(Object),
    )
  })
})
