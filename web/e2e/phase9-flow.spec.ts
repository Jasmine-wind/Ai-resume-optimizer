import { readFileSync } from 'node:fs'
import { expect, test, type Page, type TestInfo } from '@playwright/test'

const standardFixture = new URL('./fixtures/synthetic-java-resume.pdf', import.meta.url).pathname
const twoPageFixture = new URL('./fixtures/chinese-java-two-page.pdf', import.meta.url).pathname
const mixedFixture = new URL('./fixtures/mixed-backend-platform.pdf', import.meta.url).pathname

const chineseJavaJobDescription = readFileSync(
  new URL('./fixtures/jds/java-backend-platform.txt', import.meta.url),
  'utf8',
).trim()
const englishPlatformJobDescription = readFileSync(
  new URL('./fixtures/jds/backend-platform-en.txt', import.meta.url),
  'utf8',
).trim()

const unique = (prefix: string) =>
  `${prefix.slice(0, 3)}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`

async function registerAndLogin(page: Page) {
  const username = unique('phase9')
  const email = `${username}@example.invalid`
  const password = 'phase9-safe-password'

  await page.goto('/register')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入邮箱').fill(email)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '注册', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)

  await page.getByPlaceholder('请输入用户名或邮箱').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/app/)
}

async function uploadAndStartAnalysis(
  page: Page,
  jobDescription: string,
  fixture = mixedFixture,
) {
  await page.getByTestId('home-resume-upload').setInputFiles(fixture)
  await page.getByRole('button', { name: '上传简历', exact: true }).click()
  await page.locator('#home-jd').fill(jobDescription)
  await expect(page.getByTestId('home-start-analysis')).toBeEnabled({ timeout: 30_000 })
  await page.getByTestId('home-start-analysis').click()
}

async function waitForAnalysis(page: Page) {
  await expect(page).toHaveURL(/\/job-analysis\/\d+/, { timeout: 45_000 })
  await expect(page.getByRole('region', { name: '岗位要求与证据审阅' })).toBeVisible({
    timeout: 20_000,
  })
}

async function openWorkspaceWithSavedDraft(page: Page) {
  await page.getByRole('button', { name: '修改简历', exact: true }).first().click()
  await expect(page).toHaveURL(/\/workspace\/\d+/, { timeout: 15_000 })
  const bullet = page.locator('textarea').filter({ hasText: '' }).last()
  await expect(bullet).toBeVisible({ timeout: 15_000 })
  await bullet.fill('负责 Java 后端服务开发')
  await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 15_000 })
  return bullet
}

async function openWorkspaceWithoutEditing(page: Page) {
  await page.getByRole('button', { name: '修改简历', exact: true }).first().click()
  await expect(page).toHaveURL(/\/workspace\/\d+/, { timeout: 15_000 })
  await expect(page.locator('textarea').first()).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 15_000 })
}

async function previewAndExportAll(
  page: Page,
  testInfo: TestInfo,
  prefix: string,
  assertDesktopSticky = false,
) {
  const previewButton = page.getByRole('button', { name: '预览 →', exact: true })
  if (await previewButton.isVisible()) await previewButton.click()
  await expect(
    page.locator('.preflight-section').getByText('可以导出', { exact: true }),
  ).toBeVisible({ timeout: 45_000 })
  await expect(page.getByTitle('简历 PDF 预览')).toBeVisible({ timeout: 45_000 })
  const previewInspector = page.getByRole('complementary', { name: '导出检查器' })
  await expect(page.locator('.preview-document-toolbar strong')).toHaveText(/PDF 预览 · \d+ 页/)
  await expect(page.locator('.preview-document-toolbar')).not.toContainText('最终文档')
  await expect(
    page.getByRole('link', { name: '在新窗口打开完整 PDF', exact: true }),
  ).toBeVisible()
  await expect(previewInspector.locator('.preview-template-section h2')).toHaveText('模板')
  await expect(previewInspector.locator('.preview-template-section p')).toHaveText(
    '切换模板后需重新预览。',
  )
  await expect(previewInspector.locator('.preview-status-section')).toContainText(/已生成 · \d+ 页/)
  await expect(previewInspector.locator('.preview-state-row')).toHaveCount(0)
  await expect(previewInspector.getByText('导出前检查', { exact: true })).toBeVisible()
  if (assertDesktopSticky) {
    await expect(page.getByRole('button', { name: '导出 PDF', exact: true })).toBeInViewport()
  }

  for (const template of ['classic', 'modern', 'minimal']) {
    if (template !== 'classic') {
      await page.getByTestId(`preview-template-${template}`).click()
      await page.locator('.preview-status-section').getByRole('button', { name: /预览/ }).click()
      await expect(page.getByTitle('简历 PDF 预览')).toBeVisible({ timeout: 45_000 })
    }
    await expect(
      page.locator('.preflight-section').getByText('可以导出', { exact: true }),
    ).toBeVisible({ timeout: 45_000 })
    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出 PDF', exact: true }).click()
    const downloaded = await download
    expect(await downloaded.path()).toBeTruthy()
    await downloaded.saveAs(testInfo.outputPath(`${prefix}-${template}.pdf`))
  }
  const exportHistory = page.locator('details.export-history')
  await exportHistory.locator('summary').click()
  await expect(exportHistory).toHaveJSProperty('open', true)
  await expect(exportHistory.locator('.artifact-list li')).toHaveCount(3)
  if (assertDesktopSticky) {
    const inspectorMetrics = await previewInspector.evaluate((element) => ({
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
    }))
    expect(inspectorMetrics.scrollHeight).toBeGreaterThan(inspectorMetrics.clientHeight)
    await previewInspector.evaluate((element) => {
      element.scrollTop = element.scrollHeight
    })
    await expect(page.getByRole('button', { name: '导出 PDF', exact: true })).toBeInViewport()
    await expect(exportHistory.locator('.artifact-list li').last()).toBeInViewport()
  }
}

test('happy path: upload, analysis, workspace, deterministic suggestion, preview and export', async ({
  page,
}, testInfo) => {
  await registerAndLogin(page)
  await uploadAndStartAnalysis(page, englishPlatformJobDescription, mixedFixture)
  await waitForAnalysis(page)
  // A direct result route remains usable after a browser refresh.
  await page.reload()
  await expect(page.getByRole('region', { name: '岗位要求与证据审阅' })).toBeVisible({
    timeout: 20_000,
  })

  const bullet = await openWorkspaceWithSavedDraft(page)
  const bulletLine = bullet.locator('xpath=ancestor::div[contains(@class, "bullet-line")]')
  await bulletLine.getByRole('button', { name: 'AI 优化', exact: true }).click()
  const suggestionFinished = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && response.url().includes('/bullet-suggestion'),
  )
  await page.locator('[role="menuitem"]:visible', { hasText: '精简' }).click()
  await suggestionFinished
  // Demo Provider intentionally returns the frozen original. The no-op guard must
  // discard it rather than expose a misleading Apply action.
  await expect(page.getByText('正在生成修改建议…', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '采纳', exact: true })).toHaveCount(0)
  await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 15_000 })

  await page.getByRole('button', { name: '预览 →', exact: true }).click()
  await expect(
    page.locator('.preflight-section').getByText('可以导出', { exact: true }),
  ).toBeVisible({ timeout: 45_000 })
  await expect(page.getByTitle('简历 PDF 预览')).toBeVisible()
  await page.getByRole('button', { name: '返回编辑', exact: true }).click()
  await expect(page.getByTitle('简历 PDF 预览')).toBeHidden()
  await page.getByRole('button', { name: '预览 →', exact: true }).click()
  await expect(page.getByTitle('简历 PDF 预览')).toBeVisible({ timeout: 45_000 })

  await previewAndExportAll(page, testInfo, 'mixed')
})

test('returning user can reopen the same optimization from recent tasks', async ({ page }) => {
  await registerAndLogin(page)
  await uploadAndStartAnalysis(page, englishPlatformJobDescription, mixedFixture)
  await waitForAnalysis(page)
  const taskId = new URL(page.url()).pathname.split('/').pop()
  await openWorkspaceWithSavedDraft(page)
  await page.goto('/app')
  await expect(page.getByRole('heading', { name: '最近优化' })).toBeVisible({ timeout: 15_000 })
  const recentTask = page.locator('.recent-task').first()
  await expect(recentTask).toContainText('继续')
  await recentTask.getByRole('button', { name: '继续' }).click()
  await expect(page).toHaveURL(new RegExp(`/job-analysis/${taskId}$`))
})

test('standard and legal two-page fixtures export all templates', async ({ page, browser }, testInfo: TestInfo) => {
  await registerAndLogin(page)
  await uploadAndStartAnalysis(page, chineseJavaJobDescription, standardFixture)
  await waitForAnalysis(page)
  await openWorkspaceWithoutEditing(page)
  await previewAndExportAll(page, testInfo, 'standard')

  const twoPageContext = await browser.newContext({
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173',
  })
  const twoPage = await twoPageContext.newPage()
  try {
    await registerAndLogin(twoPage)
    await uploadAndStartAnalysis(twoPage, chineseJavaJobDescription, twoPageFixture)
    await waitForAnalysis(twoPage)
    await openWorkspaceWithoutEditing(twoPage)
    await previewAndExportAll(twoPage, testInfo, 'two-page')
  } finally {
    await twoPageContext.close()
  }
})

test('analysis failure recovers by retrying the same retained task without re-upload', async ({
  page,
}) => {
  await registerAndLogin(page)
  await uploadAndStartAnalysis(
    page,
    `${chineseJavaJobDescription}[[FAKE_PROVIDER_FAIL_ONCE]] ${unique('retry')}`,
    standardFixture,
  )

  await expect(page.getByText('岗位分析没有完成', { exact: true })).toBeVisible({ timeout: 45_000 })
  // Home restores the retained task after refresh, so retry does not re-upload input.
  await page.reload()
  await expect(page.getByText('岗位分析没有完成', { exact: true })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: '重试分析', exact: true }).click()
  await waitForAnalysis(page)
})

test('workspace conflict preserves the local draft; stale Preview and Suggest cannot overwrite it', async ({
  page: first,
  context,
}) => {
  await registerAndLogin(first)
  await uploadAndStartAnalysis(first, chineseJavaJobDescription, standardFixture)
  await waitForAnalysis(first)
  const originalUrl = first.url()
  const taskPath = new URL(originalUrl).pathname.replace('/job-analysis/', '/workspace/')
  await first.goto(taskPath)
  await expect(first.locator('textarea').last()).toBeVisible({ timeout: 15_000 })

  const sameContextSecond = await context.newPage()
  await sameContextSecond.goto(taskPath)
  await expect(sameContextSecond.locator('textarea').last()).toBeVisible({ timeout: 15_000 })

  const firstBullet = first.locator('textarea').last()
  await firstBullet.fill('负责 Java 后端服务开发 - first writer')
  await expect(first.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 15_000 })

  const secondBullet = sameContextSecond.locator('textarea').last()
  await secondBullet.fill('负责 Java 后端服务开发 - local conflicting draft')
  await expect(sameContextSecond.getByText('存在冲突', { exact: true })).toBeVisible({
    timeout: 15_000,
  })
  await expect(secondBullet).toHaveValue('负责 Java 后端服务开发 - local conflicting draft')

  await sameContextSecond.getByRole('button', { name: '使用线上版本', exact: true }).click()
  await expect(sameContextSecond.getByText('✓ 已保存', { exact: true })).toBeVisible({
    timeout: 15_000,
  })

  let previewRequestStarted!: () => void
  let releasePreviewResponse!: () => void
  const previewStarted = new Promise<void>((resolve) => {
    previewRequestStarted = resolve
  })
  const previewRelease = new Promise<void>((resolve) => {
    releasePreviewResponse = resolve
  })
  await first.route('**/api/workspace/*/preview.pdf*', async (route) => {
    previewRequestStarted()
    const response = await route.fetch()
    await previewRelease
    await route.fulfill({ response })
  })
  await first.getByRole('button', { name: '预览 →', exact: true }).click()
  await previewStarted
  // Preview is a focus mode; switch back to the editor while the request is pending
  // to prove the late response cannot replace a new local draft.
  await first.getByRole('button', { name: '返回编辑', exact: true }).click()
  await firstBullet.fill('负责 Java 后端服务开发 - changed while preview was pending', {
    force: true,
  })
  await expect(first.getByText('未保存', { exact: true })).toBeVisible()
  releasePreviewResponse()
  await expect(first.getByTitle('简历 PDF 预览')).toBeHidden()
  await expect(first.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 15_000 })

  let suggestionRequestStarted!: () => void
  let releaseSuggestionResponse!: () => void
  const suggestionStarted = new Promise<void>((resolve) => {
    suggestionRequestStarted = resolve
  })
  const suggestionRelease = new Promise<void>((resolve) => {
    releaseSuggestionResponse = resolve
  })
  await first.route('**/bullet-suggestion', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    suggestionRequestStarted()
    const request = JSON.parse(route.request().postData() ?? '{}') as {
      requestId: string
      bulletId: string
      baseRevision: number
      originalText: string
    }
    const response = await route.fetch()
    await response.body()
    await suggestionRelease
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: {
        'access-control-allow-origin':
          response.headers()['access-control-allow-origin'] ?? 'http://127.0.0.1:5175',
        'access-control-allow-credentials': 'true',
      },
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          requestId: request.requestId,
          state: 'READY',
          baseRevision: request.baseRevision,
          bulletId: request.bulletId,
          originalText: request.originalText,
          // Make the test response a visible candidate so the client stale guard
          // is exercised without changing the deterministic backend contract.
          suggestedText: '负责 Java 后端服务开发并持续迭代',
          reason: '测试候选',
          rejectCode: null,
          rejectMessage: null,
          modelName: 'test-model',
        },
      }),
    })
  })
  const refreshedBullet = first.locator('textarea').last()
  const bulletLine = refreshedBullet.locator('xpath=ancestor::div[contains(@class, "bullet-line")]')
  await bulletLine.getByRole('button', { name: 'AI 优化', exact: true }).click()
  await first.locator('[role="menuitem"]:visible', { hasText: '精简' }).click()
  await suggestionStarted
  await refreshedBullet.fill('负责 Java 后端服务开发 - edited while suggestion was pending')
  releaseSuggestionResponse()
  await expect(
    first.getByText('内容或版本已变化，这条建议已失效，不能采纳。可以重新生成或关闭。'),
  ).toBeVisible({ timeout: 15_000 })
  await expect(first.getByRole('button', { name: '采纳', exact: true })).toHaveCount(0)

  await sameContextSecond.close()
  await first.close()
})

test.describe('desktop preview viewport', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('keeps the export action visible while the inspector scrolls', async ({ page }, testInfo) => {
    await registerAndLogin(page)
    await uploadAndStartAnalysis(page, englishPlatformJobDescription, mixedFixture)
    await waitForAnalysis(page)
    await openWorkspaceWithoutEditing(page)
    await page.getByRole('button', { name: '预览 →', exact: true }).click()
    await previewAndExportAll(page, testInfo, 'desktop-sticky', true)
  })
})

test.describe('narrow viewport', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('keeps navigation, workspace and Preview usable without horizontal overflow', async ({
    page,
  }, testInfo) => {
    await registerAndLogin(page)
    await expect(page.getByRole('button', { name: '打开导航菜单' })).toBeVisible()

    const closedShell = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    expect(closedShell.scrollWidth).toBeLessThanOrEqual(closedShell.clientWidth)

    await page.getByRole('button', { name: '打开导航菜单' }).click()
    await expect(page.locator('.app-sidebar.is-open')).toBeVisible()
    await expect(page.getByRole('button', { name: '关闭导航菜单' })).toBeVisible()
    await page.getByRole('button', { name: '关闭导航菜单' }).click()
    await expect(page.locator('.app-sidebar.is-open')).toHaveCount(0)

    await uploadAndStartAnalysis(page, englishPlatformJobDescription, mixedFixture)
    await waitForAnalysis(page)
    await openWorkspaceWithoutEditing(page)
    await page.getByRole('button', { name: '预览 →', exact: true }).click()
    await expect(
      page.locator('.preflight-section').getByText('可以导出', { exact: true }),
    ).toBeVisible({ timeout: 45_000 })
    const frame = page.getByTitle('简历 PDF 预览')
    await expect(frame).toBeVisible({ timeout: 45_000 })
    const openPdf = page.getByRole('link', { name: '在新窗口打开完整 PDF' })
    await expect(openPdf).toBeVisible()
    await expect(openPdf).toHaveAttribute('target', '_blank')
    await expect(openPdf).toHaveAttribute('href', /blob:/)
    await expect(page.locator('.preview-template-section')).toBeInViewport()
    const previewDocument = page.locator('.preview-document')
    const previewDocumentBox = await previewDocument.boundingBox()
    expect(previewDocumentBox?.height ?? 0).toBeGreaterThanOrEqual(180)
    expect(previewDocumentBox?.height ?? 0).toBeLessThan(280)
    const narrowShell = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    expect(narrowShell.scrollWidth).toBeLessThanOrEqual(narrowShell.clientWidth)
    const frameBox = await frame.boundingBox()
    expect(frameBox?.width ?? 0).toBeLessThanOrEqual(narrowShell.clientWidth)
    await page.locator('.preview-template-section').scrollIntoViewIfNeeded()
    await expect(page.locator('.preview-status-section')).toBeVisible()
    await expect(page.locator('.preflight-section')).toBeVisible()
    await expect(page.getByRole('button', { name: '导出 PDF', exact: true })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('narrow-preview.png'), fullPage: true })
  })
})
