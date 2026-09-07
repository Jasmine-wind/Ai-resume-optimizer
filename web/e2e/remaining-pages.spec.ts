import { expect, test, type Page } from '@playwright/test'

const user = {
  id: 7,
  username: 'polish-user',
  email: 'polish@example.com',
  nickname: 'Polish User',
  createdAt: '2026-01-01T00:00:00Z',
}

const result = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data }),
})

const credential = (status: 'ACTIVE' | 'DISABLED', configured = true) => ({
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.example.com/v1',
  model: 'polish-model',
  config: {},
  status,
  configured,
  apiKeyConfigured: configured,
  maskedApiKey: configured ? 'sk-••••••' : '',
})

const installUser = async (page: Page) => {
  await page.addInitScript(() => localStorage.setItem('ai-resume-token', 'polish-token'))
  await page.route('**/api/users/me', (route) => route.fulfill(result(user)))
}

test.describe('auth pages', () => {
  test('logs in through native Enter submission, preserves redirect, and prevents duplicate requests while loading', async ({ page }) => {
    let requestCount = 0
    let releaseLogin!: () => void
    const loginReleased = new Promise<void>((resolve) => {
      releaseLogin = resolve
    })

    await page.route('**/api/auth/login', async (route) => {
      requestCount += 1
      await loginReleased
      await route.fulfill(result({
        userId: user.id,
        username: user.username,
        email: user.email,
        nickname: user.nickname,
        token: 'auth-token',
        tokenType: 'Bearer',
        expiresIn: 3600,
      }))
    })
    await page.route('**/api/users/me', (route) => route.fulfill(result(user)))
    await page.route('**/api/job-direction-insights', (route) =>
      route.fulfill(result({ cohorts: [] })),
    )
    await page.goto('/login?redirect=%2Fjob-direction-insights')
    await page.getByPlaceholder('请输入用户名或邮箱').fill('polish-user')
    await page.getByPlaceholder('请输入密码').fill('safe-password')
    await page.getByPlaceholder('请输入密码').press('Enter')
    await expect(page.getByRole('button', { name: '登录' })).toHaveClass(/is-loading/)
    await page.getByPlaceholder('请输入密码').press('Enter')
    expect(requestCount).toBe(1)
    releaseLogin()
    await expect(page).toHaveURL(/\/job-direction-insights$/)
  })

  test('keeps login failure readable without exposing backend authentication details', async ({ page }) => {
    await page.route('**/api/auth/login', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 401, message: 'internal auth detail', data: null }),
    }))
    await page.goto('/login')
    await page.getByPlaceholder('请输入用户名或邮箱').fill('polish-user')
    await page.getByPlaceholder('请输入密码').fill('wrong-password')
    await page.getByPlaceholder('请输入密码').press('Enter')
    await expect(page.locator('.auth-form-error')).toHaveText('用户名或密码错误，请检查后重试。')
    await expect(page.getByText('internal auth detail')).toHaveCount(0)
  })

  test('classifies login network and server failures separately', async ({ page }) => {
    await page.route('**/api/auth/login', (route) => route.abort('failed'))
    await page.goto('/login')
    await page.getByPlaceholder('请输入用户名或邮箱').fill('polish-user')
    await page.getByPlaceholder('请输入密码').fill('wrong-password')
    await page.getByPlaceholder('请输入密码').press('Enter')
    await expect(page.locator('.auth-form-error')).toHaveText('当前无法连接服务，请检查网络后重试')

    await page.unroute('**/api/auth/login')
    await page.route('**/api/auth/login', (route) => route.fulfill({
      status: 500, contentType: 'application/json',
      body: JSON.stringify({ code: 500, message: 'internal server detail', data: null }),
    }))
    await page.getByPlaceholder('请输入密码').press('Enter')
    await expect(page.locator('.auth-form-error')).toHaveText('服务器暂时无法处理请求，请稍后重试')
    await expect(page.getByText('internal server detail')).toHaveCount(0)
  })

  test('classifies registration business, network and server failures without developer wording', async ({ page }) => {
    await page.route('**/api/auth/register', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 409, message: '邮箱已注册', data: null }),
    }))
    await page.goto('/register')
    await page.getByPlaceholder('请输入用户名').fill('new-user')
    await page.getByPlaceholder('请输入邮箱').fill('new@example.com')
    await page.getByPlaceholder('请输入密码').fill('safe-password')
    await page.getByPlaceholder('不填写也可以').press('Enter')
    await expect(page.locator('.auth-form-error')).toHaveText('邮箱已注册')

    await page.unroute('**/api/auth/register')
    await page.route('**/api/auth/register', (route) => route.abort('failed'))
    await page.getByPlaceholder('不填写也可以').press('Enter')
    await expect(page.locator('.auth-form-error')).toHaveText('当前无法连接服务，请检查网络后重试')

    await page.unroute('**/api/auth/register')
    await page.route('**/api/auth/register', (route) => route.fulfill({
      status: 500, contentType: 'application/json',
      body: JSON.stringify({ code: 500, message: 'internal register detail', data: null }),
    }))
    await page.getByPlaceholder('不填写也可以').press('Enter')
    await expect(page.locator('.auth-form-error')).toHaveText('服务器暂时无法处理请求，请稍后重试')
  })

  test('registers and returns to login, with the optional nickname visually secondary', async ({ page }) => {
    await page.route('**/api/auth/register', (route) => route.fulfill(result({ userId: 8 })))
    await page.goto('/register')
    await page.getByPlaceholder('请输入用户名').fill('new-user')
    await page.getByPlaceholder('请输入邮箱').fill('new@example.com')
    await page.getByPlaceholder('请输入密码').fill('safe-password')
    await page.getByPlaceholder('不填写也可以').press('Enter')
    await expect(page).toHaveURL(/\/login$/)
  })

  test('places the form before trust context on a narrow viewport without horizontal overflow', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/login')
    await expect(page.locator('.auth-form-panel')).toBeVisible()
    await expect(page.locator('.auth-context')).toBeVisible()
    const layout = await page.evaluate(() => ({
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: document.documentElement.clientWidth,
      formTop: document.querySelector('.auth-form-panel')?.getBoundingClientRect().top ?? 0,
      contextTop: document.querySelector('.auth-context')?.getBoundingClientRect().top ?? 0,
    }))
    expect(layout.documentWidth).toBeLessThanOrEqual(layout.viewportWidth)
    expect(layout.formTop).toBeLessThan(layout.contextTop)
  })
})

test.describe('profile settings', () => {
  test('updates the display name immediately while keeping account identifiers read only', async ({ page }) => {
    let current: Omit<typeof user, 'nickname'> & { nickname: string | null } = { ...user }
    await page.addInitScript(() => localStorage.setItem('ai-resume-token', 'polish-token'))
    await page.route('**/api/users/me', async (route) => {
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON() as { nickname: string }
        current = { ...current, nickname: body.nickname.trim() || null }
      }
      await route.fulfill(result(current))
    })

    await page.goto('/settings/profile')
    await expect(page.getByText(user.username, { exact: true })).toBeVisible()
    await expect(page.locator('#app-main-content').getByText(user.email, { exact: true })).toBeVisible()
    const nickname = page.getByLabel('显示名称', { exact: true })
    await expect(page.getByRole('button', { name: '保存个人资料' })).toBeDisabled()
    await nickname.fill('李明')
    await page.getByRole('button', { name: '保存个人资料' }).click()
    await expect(page.getByRole('status')).toContainText('个人资料已保存')
    await expect(page.getByRole('button', { name: '账号菜单：李明' })).toBeVisible()
  })
})

test.describe('AI settings', () => {
  test.beforeEach(async ({ page }) => {
    await installUser(page)
  })

  test('keeps the system AI as the default when no personal API is configured', async ({ page }) => {
    await page.route('**/api/settings/ai-provider', (route) => route.fulfill(result(credential('DISABLED', false))))
    await page.goto('/settings/ai-provider')
    await expect(page.getByRole('heading', { name: '当前使用的 AI' })).toBeVisible()
    await expect(page.getByText('系统 AI', { exact: true })).toBeVisible()
    await expect(page.getByText('当前使用服务器配置的 AI，无需先配置自己的 API。')).toBeVisible()
  })

  test('keeps test available but disables save when secure storage is unavailable', async ({ page }) => {
    await page.route('**/api/settings/ai-provider', (route) => route.fulfill(result({
      ...credential('DISABLED', false),
      credentialStorageAvailable: false,
      systemProviderConfigured: false,
    })))
    await page.goto('/settings/ai-provider')
    await page.getByLabel('连接地址').fill('https://api.example.com/v1')
    await page.getByLabel('API 密钥').fill('secret-key')
    await page.getByLabel('模型').fill('polish-model')
    await expect(page.getByRole('button', { name: '测试连接' })).toBeEnabled()
    await expect(page.getByRole('button', { name: '保存配置' })).toBeDisabled()
    await expect(page.getByText('AI 尚未配置', { exact: true })).toBeVisible()
    await expect(page.getByText(/当前部署尚未启用 API 密钥保存/)).toBeVisible()
  })

  test('keeps the key after test, then clears it only after save and exposes the enable step', async ({ page }) => {
    let current = credential('DISABLED')
    await page.route('**/api/settings/ai-provider', (route) => route.fulfill(result(current)))
    await page.route('**/api/settings/ai-provider/test', (route) => route.fulfill(result({ success: true, message: '连接正常' })))
    await page.route('**/api/settings/ai-provider', async (route) => {
      if (route.request().method() === 'PUT') {
        current = credential('DISABLED')
        await route.fulfill(result(current))
      } else {
        await route.fulfill(result(current))
      }
    })
    await page.goto('/settings/ai-provider')
    await page.getByLabel('连接地址').fill('https://api.example.com/v1')
    await page.getByLabel('API 密钥').fill('secret-key')
    await page.getByLabel('模型').fill('polish-model')
    await page.getByRole('button', { name: '测试连接' }).click()
    await expect(page.getByLabel('API 密钥')).toHaveValue('secret-key')
    await page.getByRole('button', { name: /保存/ }).click()
    await expect(page.getByText('配置已保存，尚未启用')).toBeVisible()
    await expect(page.getByLabel('API 密钥')).toHaveValue('')
    await expect(page.getByRole('button', { name: '启用' }).first()).toBeVisible()
  })

  test('keeps the settings sequence single-column on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.route('**/api/settings/ai-provider', (route) => route.fulfill(result(credential('DISABLED'))))
    await page.goto('/settings/ai-provider')
    const layout = await page.evaluate(() => ({
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: document.documentElement.clientWidth,
    }))
    expect(layout.documentWidth).toBeLessThanOrEqual(layout.viewportWidth)
  })

  test('keeps test and save failures in the page for a recoverable retry', async ({ page }) => {
    await page.route('**/api/settings/ai-provider', (route) => route.fulfill(result(credential('DISABLED', false))))
    await page.route('**/api/settings/ai-provider/test', (route) => route.fulfill(result({ success: false, message: '连接没有通过' })))
    await page.route('**/api/settings/ai-provider', async (route) => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ code: 500, message: 'save failed', data: null }),
        })
        return
      }
      await route.fulfill(result(credential('DISABLED', false)))
    })
    await page.goto('/settings/ai-provider')
    await page.getByLabel('连接地址').fill('https://api.example.com/v1')
    await page.getByLabel('API 密钥').fill('secret-key')
    await page.getByLabel('模型').fill('polish-model')
    await page.getByRole('button', { name: '测试连接' }).click()
    await expect(page.getByText('连接测试失败', { exact: true })).toBeVisible()
    await expect(page.getByLabel('API 密钥')).toHaveValue('secret-key')
    await page.getByRole('button', { name: '保存配置' }).click()
    await expect(page.getByText('保存配置失败', { exact: true })).toBeVisible()
    await expect(page.locator('.settings-configuration').getByText('服务器暂时无法处理请求，请稍后重试', { exact: true })).toBeVisible()
  })

  test('confirms deletion and returns to the system AI state', async ({ page }) => {
    let current = credential('DISABLED')
    await page.route('**/api/settings/ai-provider**', async (route) => {
      if (route.request().method() === 'DELETE') current = credential('DISABLED', false)
      await route.fulfill(result(current))
    })
    await page.goto('/settings/ai-provider')
    await page.getByRole('button', { name: '删除', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click()
    await expect(page.getByText('已删除你的 API 密钥')).toBeVisible()
    await expect(page.getByText('当前使用服务器配置的 AI，无需先配置自己的 API。')).toBeVisible()
  })

  test('shows active and disable state without mixing it into form actions', async ({ page }) => {
    let current = credential('ACTIVE')
    await page.route('**/api/settings/ai-provider**', async (route) => {
      if (route.request().method() === 'POST' && route.request().url().endsWith('/disable')) {
        current = credential('DISABLED')
      }
      await route.fulfill(result(current))
    })
    await page.goto('/settings/ai-provider')
    await expect(page.getByText('你保存的 API 已启用')).toBeVisible()
    await page.getByRole('button', { name: '停用' }).click()
    await expect(page.getByText('自己的 API 尚未启用')).toBeVisible()
  })
})

test.describe('job direction insights', () => {
  test.beforeEach(async ({ page }) => {
    await installUser(page)
  })

  test('supports requirement drill-down with real three-state counts and readable source evidence', async ({ page }) => {
    await page.route('**/api/job-direction-insights', (route) => route.fulfill(result({
      cohorts: [{
        resumeId: 1,
        resumeName: 'Java 后端简历',
        sampleSize: 8,
        minimumSampleSize: 8,
        windowStart: '2026-03-01T00:00:00Z',
        newestAnalysisAt: '2026-09-01T00:00:00Z',
        commonRequirements: [
          {
            label: 'Spring Boot 与服务治理', occurrenceCount: 6, sampleSize: 8,
            matchedCount: 3, partialEvidenceCount: 2, noEvidenceCount: 1,
            sources: [{ optimizationTaskId: 41, evidenceRequirementId: 1, requirementText: '熟悉 Spring Boot 与服务治理', matchLevel: 'MATCHED', evidences: [{ requirementEvidenceId: 1, sectionLabel: '工作经历', evidenceText: '使用 Spring Boot 维护后端服务。', supportLevel: 'SUFFICIENT' }] }],
          },
          {
            label: '一个很长的岗位要求文本，用于确认窄屏下不会被截断或造成水平溢出', occurrenceCount: 5, sampleSize: 8,
            matchedCount: 1, partialEvidenceCount: 1, noEvidenceCount: 2,
            sources: [{ optimizationTaskId: 40, evidenceRequirementId: 2, requirementText: '一个很长的岗位要求文本，用于确认窄屏下不会被截断或造成水平溢出', matchLevel: 'NO_EVIDENCE', evidences: [] }],
          },
        ],
      }],
    })))
    await page.goto('/job-direction-insights')
    await expect(page.getByRole('heading', { name: '反复出现的要求' })).toBeVisible()
    await expect(page.getByText('6 / 8 个岗位出现')).toBeVisible()
    await expect(page.getByLabel('来源与证据').getByText('已有优势 3')).toBeVisible()
    await page.getByRole('button', { name: /一个很长的岗位要求文本/ }).click()
    await expect(page.getByRole('heading', { name: /一个很长的岗位要求文本/ })).toBeVisible()
    await expect(page.getByText('当前冻结材料没有可引用的证据。')).toBeVisible()
    const layout = await page.evaluate(() => ({
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: document.documentElement.clientWidth,
    }))
    expect(layout.documentWidth).toBeLessThanOrEqual(layout.viewportWidth)
    await expect(page.getByText('任务 #')).toHaveCount(0)
  })

  test('shows loading, then lets the user switch between resume cohorts', async ({ page }) => {
    let releaseInsights!: () => void
    const insightsReleased = new Promise<void>((resolve) => {
      releaseInsights = resolve
    })
    const cohort = (resumeId: number, resumeName: string) => ({
      resumeId,
      resumeName,
      sampleSize: 8,
      minimumSampleSize: 8,
      windowStart: '2026-03-01T00:00:00Z',
      newestAnalysisAt: '2026-09-01T00:00:00Z',
      commonRequirements: [{
        label: `${resumeName} 的共同要求`, occurrenceCount: 5, sampleSize: 8,
        matchedCount: 2, partialEvidenceCount: 2, noEvidenceCount: 1,
        sources: [],
      }],
    })
    await page.route('**/api/job-direction-insights', async (route) => {
      await insightsReleased
      await route.fulfill(result({ cohorts: [cohort(1, 'Java 后端简历'), cohort(2, '平台工程简历')] }))
    })
    const navigation = page.goto('/job-direction-insights')
    await expect(page.locator('.ui-skeleton-block')).toBeVisible()
    releaseInsights()
    await navigation
    await expect(page.locator('#insight-cohort')).toBeVisible()
    await page.locator('#insight-cohort').selectOption('1')
    await expect(page.getByRole('heading', { name: '平台工程简历 的共同要求' })).toBeVisible()
  })

  test('renders empty and error as distinct recoverable states', async ({ page }) => {
    await page.route('**/api/job-direction-insights', (route) => route.fulfill(result({ cohorts: [] })))
    await page.goto('/job-direction-insights')
    await expect(page.getByText('继续按岗位分析，洞察会自然出现')).toBeVisible()

    await page.unroute('**/api/job-direction-insights')
    await page.route('**/api/job-direction-insights', (route) => route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 500, message: 'service unavailable', data: null }),
    }))
    await page.reload()
    await expect(page.getByText('方向洞察加载失败')).toBeVisible()
    await expect(page.getByText('服务器暂时无法处理请求，请稍后重试')).toBeVisible()
    await expect(page.getByText('service unavailable')).toHaveCount(0)
  })
})
