import { expect, test, type Page } from '@playwright/test'

const user = {
  id: 21,
  username: 'polish-user',
  email: 'polish@example.com',
  nickname: 'Polish User',
  createdAt: '2026-01-01T00:00:00Z',
}

const response = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data }),
})

const mockAuthenticatedShell = async (page: Page) => {
  await page.addInitScript(() => localStorage.setItem('ai-resume-token', 'app-shell-token'))
  await page.route('**/api/users/me', (route) => route.fulfill(response(user)))
  await page.route('**/api/resumes', (route) => route.fulfill(response([])))
  await page.route('**/api/job-direction-insights', (route) =>
    route.fulfill(response({ cohorts: [] })),
  )
  await page.route('**/api/optimization-tasks/recent*', (route) => route.fulfill(response([])))
  await page.route('**/api/settings/ai-provider', (route) =>
    route.fulfill(
      response({
        providerType: 'OPENAI_COMPATIBLE',
        baseUrl: '',
        model: '',
        config: {},
        status: 'DISABLED',
        configured: false,
        apiKeyConfigured: false,
        maskedApiKey: '',
        credentialStorageAvailable: true,
      }),
    ),
  )
}

test.describe('authenticated app shell', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuthenticatedShell(page)
  })

  test('keeps product navigation calm while exposing account identity', async ({ page }) => {
    await page.goto('/settings/ai-provider')

    await expect(page).toHaveTitle('AI 设置 · CV Role')
    await expect(page.locator('.app-account-avatar')).toHaveText('PU')
    await expect(page.getByRole('button', { name: '账号菜单：Polish User' })).toBeVisible()
    await expect(
      page.locator('.app-topbar-nav').getByRole('link', { name: '开始优化' }),
    ).toBeVisible()
    await expect(
      page.locator('.app-topbar-nav').getByRole('link', { name: '我的简历' }),
    ).toBeVisible()

    await page.getByRole('button', { name: '账号菜单：Polish User' }).click()
    await expect(page.getByRole('menuitem', { name: '账户设置' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: 'AI 设置' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '退出登录' })).toBeVisible()
  })

  test('account menu routes to profile settings first', async ({ page }) => {
    await page.goto('/settings/ai-provider')
    await page.getByRole('button', { name: '账号菜单：Polish User' }).click()
    await page.getByRole('menuitem', { name: '账户设置' }).click()
    await expect(page).toHaveURL(/\/settings\/profile$/)
    await expect(page.getByRole('heading', { name: '个人资料' })).toBeVisible()
  })

  test('offers a keyboard skip link and moves focus to the new route', async ({ page }) => {
    await page.goto('/settings/ai-provider')

    const skipLink = page.getByRole('link', { name: '跳到主要内容' })
    await skipLink.focus()
    await expect(skipLink).toBeFocused()
    await skipLink.press('Enter')
    await expect(page.locator('#app-main-content')).toBeFocused()

    const appPage = page.locator('.app-page')
    await page.locator('.app-content').evaluate((element) => {
      element.setAttribute('style', 'min-height: 200vh')
    })
    await appPage.evaluate((element) => {
      element.scrollTop = 320
    })
    await expect.poll(() => appPage.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)

    await page.locator('.app-topbar-nav').getByRole('link', { name: '开始优化' }).click()
    await expect(page).toHaveURL(/\/app$/)
    await expect(page).toHaveTitle('首页 · CV Role')
    await expect(page.locator('#app-main-content')).toBeFocused()
    await expect.poll(() => appPage.evaluate((element) => element.scrollTop)).toBe(0)
  })

  test('turns mobile navigation into a closable drawer and restores trigger focus', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/settings/ai-provider')

    const menuButton = page.getByRole('button', { name: '打开导航菜单' })
    await menuButton.click()

    const drawer = page.locator('#app-primary-navigation')
    await expect(drawer).toHaveAttribute('aria-hidden', 'false')
    const closeButton = drawer.getByRole('button', { name: '关闭导航菜单' })
    await expect(closeButton).toHaveCount(1)
    await expect(closeButton).toBeVisible()
    await expect(page.getByRole('button', { name: '关闭导航菜单' })).toHaveCount(1)
    await expect(drawer.locator('.app-sidebar-link').first()).toBeFocused()
    await expect(page.locator('.app-page')).toHaveJSProperty('inert', true)
    await expect(page.locator('.app-shell-overlay')).toHaveAttribute('aria-hidden', 'true')

    await closeButton.click()
    await expect(drawer).toHaveAttribute('aria-hidden', 'true')
    await expect(menuButton).toBeFocused()
    await expect(page.locator('.app-page')).toHaveJSProperty('inert', false)

    await menuButton.click()
    await page.keyboard.press('Escape')
    await expect(drawer).toHaveAttribute('aria-hidden', 'true')
    await expect(menuButton).toBeFocused()

    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
  })

  test('keeps the shell within the viewport across supported breakpoints', async ({ page }) => {
    for (const width of [390, 430, 768, 1024, 1366, 1440, 1920]) {
      await page.setViewportSize({ width, height: width < 1000 ? 844 : 900 })
      await page.goto('/app')
      await expect(page.locator('#app-main-content')).toBeVisible()
      const metrics = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }))
      expect(metrics.scrollWidth, `shell overflows at ${width}px`).toBeLessThanOrEqual(
        metrics.clientWidth,
      )
      const isNarrow = width <= 960
      if (isNarrow) {
        await expect(page.getByRole('button', { name: '打开导航菜单' })).toBeVisible()
        await expect(page.locator('.app-topbar-nav')).toBeHidden()
      } else {
        await expect(page.getByRole('button', { name: '打开导航菜单' })).toBeHidden()
        await expect(page.locator('.app-topbar-nav')).toBeVisible()
      }
    }
  })
})
