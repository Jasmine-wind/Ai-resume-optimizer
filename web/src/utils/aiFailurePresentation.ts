const messages: Record<string, string> = {
  INVALID_CREDENTIAL: 'AI 密钥无效或未授权，请检查 AI 设置后重试。',
  PROVIDER_UNAUTHORIZED: 'AI 密钥无效或未授权，请检查 AI 设置后重试。',
  MODEL_NOT_FOUND: '当前 AI 模型不可用，请检查模型名称。',
  RATE_LIMITED: 'AI 服务请求过于频繁，请稍后重试。',
  TIMEOUT: 'AI 服务响应超时，请稍后重试。',
  PROVIDER_UNAVAILABLE: 'AI 服务暂时不可用，请稍后重试。',
  SCHEMA_INVALID: 'AI 返回内容暂时无法使用，请重新生成。',
  FINAL_CONTENT_MISSING: '当前模型没有返回可用的最终文本，系统已尝试兼容模式。请在 AI 设置中重新测试当前接口，或更换能够返回最终结果的模型。',
  PROVIDER_PROTOCOL_INCOMPATIBLE: '当前 AI 接口未能完成兼容的 Chat Completions 调用，请在 AI 设置中重新测试当前接口。',
  REFUSAL: 'AI 未能在当前事实范围内生成建议，可以重新生成或手工编辑。',
  UNSAFE_BASE_URL: 'AI 连接未通过安全检查，请检查 AI 设置或网络环境后重试。',
  RESPONSE_TOO_LARGE: 'AI 返回内容过长，请重新生成。',
  CREDENTIAL_CHANGED: 'AI 配置已经变化，请刷新后重试。',
  CONFIGURATION_INVALID: 'AI 配置不完整，请检查 AI 设置。',
  AI_CONFIGURATION_REQUIRED: '请先在 AI 设置中配置并启用自己的 API。',
  INTERRUPTED: 'AI 请求已中断，请重新尝试。',
}

export const presentAiFailure = (
  failureCode: string | undefined,
  fallback: string,
  scope: 'generic' | 'settings' = 'generic',
) => {
  if (scope === 'settings' && failureCode === 'UNSAFE_BASE_URL') {
    return '连接地址未通过安全检查。若地址格式正确，请检查后端运行环境的 DNS 或代理设置后重试。'
  }
  if (scope === 'settings' && failureCode === 'TIMEOUT') {
    return '连接服务超时，请检查网络后重试。'
  }
  if (scope === 'settings' && (failureCode === 'INVALID_CREDENTIAL' || failureCode === 'PROVIDER_UNAUTHORIZED')) {
    return 'API 密钥无效或未授权。'
  }
  return failureCode && Object.prototype.hasOwnProperty.call(messages, failureCode)
    ? messages[failureCode] ?? fallback
    : fallback
}

export const aiFailureMessages = messages
