import request from '@/api/request'

export type AiProviderStatus = 'ACTIVE' | 'DISABLED'

export interface AiProviderCredential {
  providerType: string
  baseUrl: string
  model: string
  config: Record<string, unknown>
  status: AiProviderStatus
  configured: boolean
  apiKeyConfigured: boolean
  maskedApiKey: string
  credentialStorageAvailable?: boolean
  systemProviderConfigured?: boolean
  credentialRevision?: number
  createdAt?: string
  updatedAt?: string
}

export interface AiProviderCredentialInput {
  baseUrl: string
  apiKey: string
  model: string
  config?: Record<string, unknown>
}

export interface AiProviderTestResult {
  success: boolean
  failureCode?: string
  message: string
}

const endpoint = '/api/settings/ai-provider'

export const getAiProviderSettings = () => request.get<AiProviderCredential>(endpoint)

export const testAiProvider = (input: AiProviderCredentialInput) =>
  request.post<AiProviderTestResult>(`${endpoint}/test`, input, { timeout: 130000 })

export const saveAiProviderSettings = (input: AiProviderCredentialInput) =>
  request.put<AiProviderCredential>(endpoint, input)

export const enableAiProvider = () => request.post<AiProviderCredential>(`${endpoint}/enable`)

export const disableAiProvider = () => request.post<AiProviderCredential>(`${endpoint}/disable`)

export const deleteAiProvider = () => request.delete<void>(endpoint)
