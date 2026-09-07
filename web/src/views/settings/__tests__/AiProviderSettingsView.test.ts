// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiProviderSettingsView from '@/views/settings/AiProviderSettingsView.vue'
import {
  deleteAiProvider,
  disableAiProvider,
  enableAiProvider,
  getAiProviderSettings,
  saveAiProviderSettings,
  testAiProvider,
} from '@/api/ai-provider'
import type { AiProviderCredential } from '@/api/ai-provider'

const { messageError, messageSuccess } = vi.hoisted(() => ({
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
}))

const elementPlusStubs = vi.hoisted(() => ({
  ElButton: {
    name: 'ElButton',
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  ElAlert: { template: '<div><slot /></div>' },
  ElForm: {
    emits: ['submit'],
    template: '<form @submit="$emit(\'submit\', $event)"><slot /></form>',
  },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: {
    props: ['modelValue', 'type', 'placeholder'],
    emits: ['update:modelValue'],
    template:
      '<input :type="type || \'text\'" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ElTag: { template: '<span><slot /></span>' },
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: messageError, success: messageSuccess },
  ElMessageBox: { confirm: vi.fn() },
  ...elementPlusStubs,
}))

vi.mock('element-plus/es', () => ({
  ...elementPlusStubs,
  ElMessage: { error: messageError, success: messageSuccess },
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('@/api/ai-provider', () => ({
  deleteAiProvider: vi.fn(),
  disableAiProvider: vi.fn(),
  enableAiProvider: vi.fn(),
  getAiProviderSettings: vi.fn(),
  saveAiProviderSettings: vi.fn(),
  testAiProvider: vi.fn(),
}))

const getSettingsMock = vi.mocked(getAiProviderSettings)
const enableMock = vi.mocked(enableAiProvider)
const disableMock = vi.mocked(disableAiProvider)
const deleteMock = vi.mocked(deleteAiProvider)
const saveMock = vi.mocked(saveAiProviderSettings)
const testMock = vi.mocked(testAiProvider)

const credential = (
  status: 'ACTIVE' | 'DISABLED',
  configured: boolean,
  credentialStorageAvailable: boolean,
  systemProviderConfigured: boolean,
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
  systemProviderConfigured,
})

const mountLoaded = async (settings: AiProviderCredential) => {
  getSettingsMock.mockResolvedValue(settings)
  const wrapper = mount(AiProviderSettingsView, {
    global: {
      stubs: {
        RouterLink: {
          props: ['to', 'ariaCurrent'],
          template: '<a :href="to"><slot /></a>',
        },
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

  it('reports activation after enabling instead of reading the post-update status backwards', async () => {
    enableMock.mockResolvedValue(credential('ACTIVE', true, true, true))
    const wrapper = await mountLoaded(credential('DISABLED', true, true, true))

    await button(wrapper, '启用').trigger('click')
    await flushPromises()

    expect(enableMock).toHaveBeenCalledOnce()
    expect(messageSuccess).toHaveBeenCalledWith('已启用你的 API 密钥')
  })

  it('reports deactivation after disabling instead of reading the post-update status backwards', async () => {
    disableMock.mockResolvedValue(credential('DISABLED', true, true, true))
    const wrapper = await mountLoaded(credential('ACTIVE', true, true, true))

    await button(wrapper, '停用').trigger('click')
    await flushPromises()

    expect(disableMock).toHaveBeenCalledOnce()
    expect(messageSuccess).toHaveBeenCalledWith('已停用你的 API，新任务将使用系统 AI。')
  })

  it('reports no available AI after disabling BYOK when the system provider is unavailable', async () => {
    disableMock.mockResolvedValue(credential('DISABLED', true, true, false))
    const wrapper = await mountLoaded(credential('ACTIVE', true, true, false))

    await button(wrapper, '停用').trigger('click')
    await flushPromises()

    expect(messageSuccess).toHaveBeenCalledWith('已停用你的 API；当前没有可用 AI。')
  })

  it('uses System AI when saved BYOK is inactive and the system provider is configured', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', true, true, true))

    expect(wrapper.text()).toContain('系统 AI')
    expect(wrapper.text()).toContain('当前使用服务器配置的 AI，无需配置自己的 API。')
    expect(wrapper.text()).not.toContain('系统 AI 会继续工作')
  })

  it('fails closed when no AI is configured and does not promise a system fallback', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', false, true, false))

    expect(wrapper.text()).toContain('AI 尚未配置')
    expect(wrapper.text()).toContain('当前没有可用的 AI 配置。')
    expect(wrapper.text()).not.toContain('系统 AI')
    expect(wrapper.text()).not.toContain('新任务将使用系统 AI')
    expect(wrapper.text()).not.toContain('系统 AI 会继续工作')
  })

  it('explains that an inactive saved API is the only unavailable state', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', true, true, false))

    expect(wrapper.text()).toContain('AI 尚未配置')
    expect(wrapper.text()).toContain('你的 API 已保存但尚未启用；当前没有其它可用 AI。')
    expect(wrapper.text()).not.toContain('系统 AI 会继续工作')
    expect(wrapper.text()).not.toContain('新任务使用系统 AI')
  })

  it('presents an active BYOK credential as the effective AI', async () => {
    const wrapper = await mountLoaded(credential('ACTIVE', true, true, false))

    expect(wrapper.text()).toContain('你的 API')
    expect(wrapper.text()).toContain('你保存的 API 已启用，新任务会使用它。')
  })

  it('retains the key after a successful test and clears it only after save', async () => {
    testMock.mockResolvedValue({ success: true, message: '连接正常' })
    saveMock.mockResolvedValue(credential('DISABLED', true, true, true))
    const wrapper = await mountLoaded(credential('DISABLED', false, true, true))
    const inputs = wrapper.findAll('input')

    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')
    await button(wrapper, '测试连接').trigger('click')
    await flushPromises()

    expect(inputs[1]!.element.value).toBe('secret-key')
    expect(button(wrapper, '保存配置').attributes('disabled')).toBeUndefined()

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(inputs[1]!.element.value).toBe('')
    expect(saveMock).toHaveBeenCalledOnce()
  })

  it('retains the key after a failed test', async () => {
    testMock.mockResolvedValue({ success: false, message: '连接没有通过' })
    const wrapper = await mountLoaded(credential('DISABLED', false, true, true))
    const inputs = wrapper.findAll('input')

    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')
    await button(wrapper, '测试连接').trigger('click')
    await flushPromises()

    expect(inputs[1]!.element.value).toBe('secret-key')
    expect(wrapper.text()).toContain('连接测试失败')
  })

  it('clears an old test result whenever any connection field changes', async () => {
    testMock.mockResolvedValue({ success: true, message: '连接正常' })
    const wrapper = await mountLoaded(credential('DISABLED', false, true, true))
    const inputs = wrapper.findAll('input')

    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')
    await button(wrapper, '测试连接').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('连接测试成功')

    await inputs[2]!.setValue('new-model')
    await flushPromises()
    expect(wrapper.text()).not.toContain('连接测试成功')
  })

  it('allows Test but disables Save when credential storage is unavailable', async () => {
    const wrapper = await mountLoaded(credential('DISABLED', false, false, false))
    const inputs = wrapper.findAll('input')

    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')

    expect(button(wrapper, '测试连接').attributes('disabled')).toBeUndefined()
    expect(button(wrapper, '保存配置').element.disabled).toBe(true)
    expect(wrapper.text()).toContain('当前部署尚未启用 API 密钥保存')
  })

  it('fails closed when capability fields are missing from the response', async () => {
    const response = {
      ...credential('DISABLED', false, false, false),
      credentialStorageAvailable: undefined,
      systemProviderConfigured: undefined,
    }
    const wrapper = await mountLoaded(response)
    const inputs = wrapper.findAll('input')

    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')

    expect(wrapper.text()).toContain('AI 尚未配置')
    expect(wrapper.text()).not.toContain('系统 AI')
    expect(button(wrapper, '测试连接').attributes('disabled')).toBeUndefined()
    expect(button(wrapper, '保存配置').element.disabled).toBe(true)
  })

  it('branches the saved next step and delete confirmation on system availability', async () => {
    saveMock.mockResolvedValue(credential('DISABLED', true, true, false))
    const wrapper = await mountLoaded(credential('DISABLED', false, true, false))
    const inputs = wrapper.findAll('input')
    await inputs[0]!.setValue('https://api.example.com/v1')
    await inputs[1]!.setValue('secret-key')
    await inputs[2]!.setValue('test-model')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('启用后，新任务才能使用 AI；当前没有其它可用 AI。')

    const confirm = vi.mocked((await import('element-plus')).ElMessageBox.confirm)
    confirm.mockResolvedValueOnce(true as never)
    deleteMock.mockResolvedValue(undefined)
    getSettingsMock.mockResolvedValue(credential('DISABLED', false, true, false))
    await button(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledWith(
      '删除后，你保存的 API 密钥将被移除；在重新配置或启用 AI 前，新任务无法使用 AI 功能。是否确认删除？',
      '删除你的 API 密钥',
      expect.any(Object),
    )
  })
})
