import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, AxiosResponse } from 'axios'
import type { ApiResult } from '@/types/auth'
import { clearAuthToken, readAuthToken } from '@/utils/auth-token'

interface ApiClient {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>
}

const resolveApiBaseUrl = () => {
  const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL ?? ''
  const normalizedBaseUrl = configuredBaseUrl.trim().replace(/\/+$/, '')

  if (normalizedBaseUrl === '/api') {
    return ''
  }

  if (normalizedBaseUrl.endsWith('/api')) {
    return normalizedBaseUrl.slice(0, -4)
  }

  return normalizedBaseUrl
}

const service = axios.create({
  baseURL: resolveApiBaseUrl(),
  timeout: 10000,
})

const redirectUnauthorized = () => {
  clearAuthToken()

  if (window.location.pathname !== '/login') {
    const redirect = `${window.location.pathname}${window.location.search}`
    window.location.href = `/login?redirect=${encodeURIComponent(redirect)}`
  }
}

// 只翻译完整匹配的 AI 错误标识；普通业务文案保持原样，业务码仍使用后端整数。
const aiFailureMessages = new Map<string, string>([
  ['INVALID_CREDENTIAL', 'AI 密钥无效或未授权，请检查 AI 设置后重试。'],
  ['PROVIDER_UNAUTHORIZED', 'AI 密钥无效或未授权，请检查 AI 设置后重试。'],
  ['MODEL_NOT_FOUND', '当前 AI 模型不可用，请检查模型名称。'],
  ['RATE_LIMITED', 'AI 服务请求过于频繁，请稍后重试。'],
  ['TIMEOUT', 'AI 服务响应超时，请稍后重试。'],
  ['PROVIDER_UNAVAILABLE', 'AI 服务暂时不可用，请稍后重试。'],
  ['SCHEMA_INVALID', 'AI 返回内容暂时无法使用，请重新生成。'],
  ['REFUSAL', 'AI 未能在当前事实范围内生成建议，可以重新生成或手工编辑。'],
  ['UNSAFE_BASE_URL', 'AI 连接未通过安全检查，请检查 AI 设置或网络环境后重试。'],
  ['RESPONSE_TOO_LARGE', 'AI 返回内容过长，请重新生成。'],
  ['CREDENTIAL_CHANGED', 'AI 配置已经变化，请刷新后重试。'],
  ['CONFIGURATION_INVALID', 'AI 配置不完整，请检查 AI 设置。'],
  ['INTERRUPTED', 'AI 请求已中断，请重新尝试。'],
])

const translateErrorMessage = (message: string | undefined, fallback: string) =>
  (message && (aiFailureMessages.get(message) ?? message)) || fallback

const unwrapResponse = <T>(response: AxiosResponse<ApiResult<T>>) => {
  const result = response.data

  if (result.code !== 200) {
    // 认证失败和服务端故障使用稳定的用户文案，避免把后端内部详情泄露到登录 / 注册表单。
    const isAuthEndpoint = response.config.url?.includes('/api/auth/') === true
    const message =
      isAuthEndpoint && result.code === 401
        ? '用户名或密码错误，请检查后重试。'
        : isAuthEndpoint && result.code >= 500
          ? '服务器暂时无法处理请求，请稍后重试'
          : translateErrorMessage(result.message, '请求失败')
    // 附带业务码（如 409 revision 失效），调用方可据此做失效处理而不是只提示。
    const apiError = new Error(message) as Error & { code?: number }
    apiError.code = result.code
    throw apiError
  }

  return result.data
}

service.interceptors.request.use((config) => {
  const token = readAuthToken()

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

service.interceptors.response.use(
  undefined,
  async (error: AxiosError<ApiResult<unknown> | Blob>) => {
    // responseType: blob 也会把非 2xx JSON 错误读成 Blob，先还原再走同一套错误处理。
    if (error.response?.data instanceof Blob) {
      const contentType = String(error.response.headers['content-type'] ?? '').toLowerCase()
      if (contentType.includes('application/json')) {
        try {
          error.response.data = JSON.parse(await error.response.data.text()) as ApiResult<unknown>
        } catch {
          // 无法解析时保留 HTTP 状态和默认错误文案。
        }
      }
    }
    const normalizedError = error as AxiosError<ApiResult<unknown>>
    const status = error.response?.status
    const code = normalizedError.response?.data?.code
    const message = resolveErrorMessage(normalizedError)

    if (status === 401 || code === 401) {
      redirectUnauthorized()
    }

    // 附带业务码（如 409 revision 失效），调用方可据此做失效处理而不是只提示。
    const apiError = new Error(message) as Error & { code?: number }
    apiError.code = code ?? status
    return Promise.reject(apiError)
  },
)

const resolveErrorMessage = (error: AxiosError<ApiResult<unknown>>) => {
  const status = error.response?.status
  const code = error.response?.data?.code
  if (status === 401 || code === 401) {
    return '用户名或密码错误，请检查后重试。'
  }
  if ((status !== undefined && status >= 500) || (code !== undefined && code >= 500)) {
    return '服务器暂时无法处理请求，请稍后重试'
  }
  const serverMessage = error.response?.data?.message
  if (serverMessage) {
    return translateErrorMessage(serverMessage, '请求失败')
  }

  if (error.code === 'ECONNABORTED') {
    return '请求超时，请稍后重试'
  }

  if (!error.response) {
    return '当前无法连接服务，请检查网络后重试'
  }

  if (error.response.status >= 500) {
    return '服务器暂时无法处理请求，请稍后重试'
  }

  return error.message || '请求失败'
}

const request: ApiClient = {
  get: <T>(url: string, config?: AxiosRequestConfig) => {
    return service.get<ApiResult<T>>(url, config).then(unwrapResponse)
  },
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => {
    return service.post<ApiResult<T>>(url, data, config).then(unwrapResponse)
  },
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => {
    return service.patch<ApiResult<T>>(url, data, config).then(unwrapResponse)
  },
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => {
    return service.put<ApiResult<T>>(url, data, config).then(unwrapResponse)
  },
  delete: <T>(url: string, config?: AxiosRequestConfig) => {
    return service.delete<ApiResult<T>>(url, config).then(unwrapResponse)
  },
}

export default request

/**
 * 携带 JWT 下载二进制内容（PDF Preview / Export）。
 * 后端业务错误统一为 HTTP 200 + Result JSON（见 GlobalExceptionHandler），
 * 因此成功响应必须按 Content-Type 区分真正的 PDF 与包在 200 里的错误 JSON；
 * 非 2xx 错误由响应拦截器归一化为带业务码的 Error，这里直接透传。
 */
export interface DownloadedPdfResponse {
  blob: Blob
  headers: Record<string, string>
}

export const downloadPdfResponse = async (
  url: string,
  timeoutMs = 60000,
): Promise<DownloadedPdfResponse> => {
  const response = await service.get<Blob>(url, { responseType: 'blob', timeout: timeoutMs })

  const contentType = String(response.headers['content-type'] ?? '').toLowerCase()
  if (contentType.includes('application/json')) {
    let parsed: ApiResult<unknown> | null = null
    try {
      parsed = JSON.parse(await response.data.text()) as ApiResult<unknown>
    } catch {
      // 声明为 JSON 却无法解析：fail closed，绝不把坏字节交给预览或下载。
    }
    if (parsed?.code === 401) {
      redirectUnauthorized()
    }
    const apiError = new Error(
      translateErrorMessage(parsed?.message, '下载失败，请稍后重试'),
    ) as Error & { code?: number }
    apiError.code = parsed?.code
    throw apiError
  }
  if (!contentType.includes('application/pdf')) {
    throw new Error('下载响应不是有效 PDF')
  }
  const signature = new TextDecoder('ascii').decode(await response.data.slice(0, 5).arrayBuffer())
  if (signature !== '%PDF-') {
    throw new Error('下载响应不是有效 PDF')
  }
  const headers: Record<string, string> = {}
  for (const [name, value] of Object.entries(response.headers)) {
    if (value !== undefined && value !== null) headers[name.toLowerCase()] = String(value)
  }
  return { blob: response.data, headers }
}

export const downloadBlob = async (url: string, timeoutMs = 60000): Promise<Blob> =>
  (await downloadPdfResponse(url, timeoutMs)).blob
