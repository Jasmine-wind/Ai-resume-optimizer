import { expect, test, type Page } from '@playwright/test'

const readyResume = {
  id: 1,
  originalFilename: '林然-产品分析简历-2026.pdf',
  displayName: '产品分析 · 一页精简版',
  fileType: 'PDF',
  fileSize: 120000,
  uploadStatus: 'SUCCESS',
  parseStatus: 'SUCCESS',
  qualityStatus: 'READY',
  canonicalReady: true,
  parseErrorMessage: null,
  createdAt: '2026-01-01T00:00:00Z',
}

const reviewResume = {
  ...readyResume,
  id: 2,
  originalFilename: 'resume-product-analytics-long-name.docx',
  displayName: '产品分析 · 待确认版',
  fileType: 'DOCX',
  qualityStatus: 'NEEDS_REVIEW',
}

const response = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data }),
})

const recentTask = (optimizationTaskId: number, status: string, jobTitle: string) => ({
  optimizationTaskId,
  jobTitle,
  resumeName: '一页精简版',
  status,
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-01T10:00:00Z',
})

async function mockHome(
  page: Page,
  resumes = [readyResume, reviewResume],
  insights: unknown = { cohorts: [] },
  recentTasks: unknown[] = [],
) {
  await page.addInitScript(() => {
    window.localStorage.setItem('ai-resume-token', 'home-composer-test-token')
  })
  await page.route('**/api/users/me', (route) =>
    route.fulfill(
      response({
        id: 1,
        username: 'home-test',
        email: 'home@example.invalid',
        nickname: '首页测试用户',
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ),
  )
  await page.route('**/api/resumes', (route) => route.fulfill(response(resumes)))
  await page.route('**/api/settings/ai-provider', (route) =>
    route.fulfill(response({
      providerType: 'OPENAI_COMPATIBLE',
      baseUrl: 'https://api.example.invalid/v1',
      model: 'e2e-model',
      config: {},
      status: 'ACTIVE',
      configured: true,
      apiKeyConfigured: true,
      maskedApiKey: '••••••••',
      credentialStorageAvailable: true,
    })),
  )
  await page.route('**/api/job-direction-insights', (route) => route.fulfill(response(insights)))
  await page.route('**/api/optimization-tasks/recent*', (route) =>
    route.fulfill(response(recentTasks)),
  )
}

test.describe('Job Target Composer', () => {
  test('makes selection, JD input and blocking reason explicit', async ({ page }) => {
    await mockHome(page)
    await page.goto('/app')

    await expect(page.getByRole('heading', { name: '针对一个岗位优化简历' })).toBeVisible()
    await expect(page.locator('.ui-page-eyebrow')).toHaveCount(0)
    await expect(page.getByRole('radio', { name: /林然-产品分析简历/ })).toBeChecked()
    await expect(page.getByTestId('home-start-analysis')).toBeDisabled()
    await expect(page.locator('.home-action-summary strong')).toHaveText(
      '粘贴完整岗位描述后即可开始核对。',
    )

    await page.locator('label.home-resume-option').nth(1).click()
    await expect(page.getByRole('radio', { name: /resume-product-analytics/ })).toBeChecked()
    await expect(page.locator('.home-resume-state')).toContainText('需要确认')
    await expect(page.locator('.home-inline-link')).toContainText('前往确认')

    await page.locator('#home-jd').fill('负责数据分析与跨团队协作。')
    await expect(page.locator('.el-input__count')).toHaveText('13 / 10000')
    await expect(page.locator('.home-resume-option.is-selected strong')).toHaveText(
      '产品分析 · 待确认版',
    )
    await expect(page.locator('.home-resume-option.is-selected small')).toContainText(
      'resume-product-analytics-long-name.docx',
    )
    await expect(page.locator('.home-jd-field textarea')).toHaveAttribute('maxlength', '10000')
    await expect(page.locator('.home-jd-field textarea')).toHaveCSS('resize', 'vertical')
    await expect(page.getByTestId('home-start-analysis')).toBeDisabled()
    await expect(page.locator('.home-action-summary strong')).toHaveText('这份简历有内容需要确认。')

    await page.locator('label.home-resume-option').first().click()
    await expect(page.getByTestId('home-start-analysis')).toBeEnabled()
    await expect(page.locator('.home-resume-option.is-selected')).toHaveClass(/is-selected/)
    await expect(page.getByText('已选', { exact: true })).toHaveCount(0)
    await expect(page.locator('.home-action-label')).toHaveCount(0)
    await expect(page.locator('.home-action-summary strong')).toContainText('将使用')
  })

  test('starts one background analysis and locks the composer against duplicates', async ({
    page,
  }) => {
    await mockHome(page, [readyResume])
    let startCount = 0
    await page.route('**/api/job-analyses', (route) => {
      startCount += 1
      return route.fulfill(
        response({
          taskId: 123,
          optimizationTaskId: 456,
          sourceResumeVersionId: 11,
          targetResumeVersionId: 12,
          jobTargetId: 13,
        }),
      )
    })
    await page.route('**/api/tasks/123', (route) =>
      route.fulfill(
        response({
          taskId: 123,
          taskType: 'JOB_ANALYSIS',
          status: 'RUNNING',
          progress: 0,
          message: '正在拆解岗位要求',
        }),
      ),
    )

    await page.goto('/app')
    await page.locator('#home-jd').fill('负责数据分析与跨团队协作。')
    const startButton = page.getByTestId('home-start-analysis')
    await expect(startButton).toBeEnabled()
    await startButton.click()
    await expect(page.locator('.home-analysis-state')).toContainText('岗位分析正在后台进行')
    await expect(page.locator('.home-analysis-state')).toContainText('正在拆解岗位要求')
    await expect(page.locator('#home-jd')).toBeDisabled()
    await expect(startButton).toBeDisabled()
    expect(startCount).toBe(1)
  })

  test('restores the active task and JD input directly after refresh', async ({ page }) => {
    await mockHome(page, [readyResume])
    await page.addInitScript(() => {
      window.sessionStorage.setItem(
        'cv-role:active-job-analysis',
        JSON.stringify({
          taskId: 123,
          optimizationTaskId: 456,
          sourceResumeVersionId: 11,
          targetResumeVersionId: 12,
          jobTargetId: 13,
          resumeId: 1,
          jobDescription: '恢复后的岗位描述',
          startedAt: '2026-01-01T00:00:00Z',
        }),
      )
    })
    await page.route('**/api/tasks/123', (route) =>
      route.fulfill(
        response({
          taskId: 123,
          taskType: 'JOB_ANALYSIS',
          status: 'RUNNING',
          progress: 0,
          message: '正在恢复岗位分析',
        }),
      ),
    )

    await page.goto('/app')
    await expect(page.locator('.home-analysis-state')).toContainText('岗位分析正在后台进行')
    await expect(page.locator('#home-jd')).toHaveValue('恢复后的岗位描述')
    await expect(page.locator('#home-jd')).toBeDisabled()
  })

  test('shows a useful empty state and keeps the JD field available', async ({ page }) => {
    await mockHome(page, [])
    await page.goto('/app')

    await expect(page.locator('.home-empty-source')).toContainText('还没有可用简历')
    await expect(page.getByRole('button', { name: '上传第一份简历' })).toBeVisible()
    await expect(page.locator('.home-inline-upload')).toContainText('10 MB')
    await expect(page.locator('#home-jd')).toBeVisible()
    await expect(page.locator('.home-action-summary strong')).toHaveText(
      '请选择一份可以用于分析的简历。',
    )
  })

  test('keeps the primary task and CTA within the first desktop viewport', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await mockHome(page)
    await page.goto('/app')
    await expect(page.locator('.home-task-bar')).toBeVisible()

    const taskBarBottom = await page
      .locator('.home-task-bar')
      .evaluate((element) => element.getBoundingClientRect().bottom)
    expect(taskBarBottom).toBeLessThanOrEqual(768)
    await expect(page.getByRole('heading', { name: '粘贴完整岗位描述' })).toBeVisible()
    await expect(page.locator('label.home-sr-only')).toHaveText('目标岗位 JD')
  })

  test('keeps the recent optimization heading and first task in the 1440px first viewport', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const recentTasks = [1, 2, 3, 4, 5].map((id) =>
      recentTask(id, 'SUCCESS', `Java 后端岗位 ${id}`),
    )
    await mockHome(page, [readyResume], { cohorts: [] }, recentTasks)
    await page.goto('/app')

    await expect(page.getByRole('heading', { name: '最近优化' })).toBeInViewport()
    await expect(page.locator('.recent-task').first()).toBeInViewport()
    await expect(page.locator('.recent-task').first()).toContainText('Java 后端岗位 1')
    await expect(page.locator('.recent-task').first()).toContainText('已完成')
    await expect(
      page.locator('.recent-task').first().getByRole('button', { name: '继续' }),
    ).toBeVisible()
  })

  test('keeps recent statuses textual and delete secondary on desktop, visible on mobile', async ({
    page,
  }) => {
    const recentTasks = [
      recentTask(1, 'SUCCESS', 'Java 后端工程师'),
      recentTask(2, 'FAILED', '平台工程师'),
      recentTask(3, 'RUNNING', '服务端工程师'),
      recentTask(4, 'PENDING', '数据工程师'),
      recentTask(5, 'CANCELLED', '已取消岗位'),
    ]
    await mockHome(page, [readyResume], { cohorts: [] }, recentTasks)
    await page.route('**/api/optimization-tasks/1', (route) => route.fulfill(response(null)))
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/app')

    await expect(page.locator('.recent-task').nth(0)).toContainText('已完成')
    await expect(
      page.locator('.recent-task').nth(0).getByRole('button', { name: '继续' }),
    ).toBeVisible()
    await expect(page.locator('.recent-task').nth(1)).toContainText('分析失败')
    await expect(
      page.locator('.recent-task').nth(1).getByRole('button', { name: '重新分析' }),
    ).toBeVisible()
    await expect(page.locator('.recent-task').nth(2)).toContainText('分析中')
    await expect(page.locator('.recent-task').nth(3)).toContainText('分析中')
    await expect(page.locator('.recent-task').nth(4)).toContainText('已取消')
    for (const index of [2, 3, 4]) {
      await expect(page.locator('.recent-task').nth(index).getByRole('button')).toHaveCount(1)
    }

    for (const row of await page.locator('.recent-task').all()) {
      const actions = row.locator('.recent-task-actions')
      await expect(actions.locator('.recent-task-status')).toHaveCount(1)
      await expect(row.locator('.recent-task-copy .recent-task-status')).toHaveCount(0)
      await expect(actions).toHaveCSS('align-items', 'baseline')
      const geometry = await actions.evaluate((element) => {
        const children = Array.from(element.children)
        return children.slice(1).map((child, index) => ({
          gap: child.getBoundingClientRect().left - children[index]!.getBoundingClientRect().right,
          margin: getComputedStyle(child).marginLeft,
        }))
      })
      for (const item of geometry) {
        expect(item.gap).toBeCloseTo(12, 0)
        expect(item.margin).toBe('0px')
      }
    }

    const desktopDelete = page
      .locator('.recent-task')
      .first()
      .getByRole('button', { name: /删除岗位优化记录/ })
    await expect(desktopDelete).toHaveCSS('opacity', '0')
    await page.locator('.recent-task').first().hover()
    await expect(desktopDelete).toHaveCSS('opacity', '1')
    await page.mouse.move(10, 10)
    await expect(desktopDelete).toHaveCSS('opacity', '0')
    await page.locator('.recent-task').first().getByRole('button', { name: '继续' }).focus()
    await page.keyboard.press('Tab')
    await expect(desktopDelete).toBeFocused()
    await expect(desktopDelete).toHaveCSS('opacity', '1')
    await expect(desktopDelete).toHaveCSS('outline-style', 'solid')

    await page.setViewportSize({ width: 390, height: 844 })
    await expect(desktopDelete).toBeVisible()
    await expect(desktopDelete).toHaveCSS('opacity', '1')
    await expect(page.locator('.recent-task').nth(0).locator('.recent-task-actions')).toContainText(
      '已完成',
    )
    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 844 })
      for (const row of await page.locator('.recent-task').all()) {
        const layout = await row.evaluate((element) => {
          const copy = element.querySelector('.recent-task-copy')!.getBoundingClientRect()
          const actions = element.querySelector('.recent-task-actions')!
          const bounds = actions.getBoundingClientRect()
          return {
            left: bounds.left - copy.left,
            below: bounds.top >= copy.bottom,
            fits: actions.scrollWidth <= actions.clientWidth,
          }
        })
        expect(layout.left).toBeCloseTo(0, 0)
        expect(layout.below).toBe(true)
        expect(layout.fits).toBe(true)
      }
    }
    const mobileShell = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    expect(mobileShell.scrollWidth).toBeLessThanOrEqual(mobileShell.clientWidth)
  })

  test('keeps delete discoverable on a wide touch device without hover', async ({ browser }) => {
    const context = await browser.newContext({
      hasTouch: true,
      viewport: { width: 1024, height: 900 },
    })
    const page = await context.newPage()
    try {
      await mockHome(page, [readyResume], { cohorts: [] }, [
        recentTask(1, 'SUCCESS', 'Java 后端工程师'),
      ])
      await page.goto('/app')
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true)
      const remove = page.getByRole('button', { name: /删除岗位优化记录/ })
      await expect(remove).toHaveCSS('opacity', '1')
      await expect(remove).toHaveCSS('pointer-events', 'auto')
      await remove.tap()
      await expect(page.getByRole('dialog')).toBeVisible()
      await page.getByRole('button', { name: '取消', exact: true }).click()
      await expect(page.locator('.recent-task')).toHaveCount(1)
    } finally {
      await context.close()
    }
  })

  test('deletes a recent optimization in place without changing the delete contract', async ({
    page,
  }) => {
    await mockHome(page, [readyResume], { cohorts: [] }, [
      recentTask(1, 'SUCCESS', 'Java 后端工程师'),
    ])
    await page.route('**/api/optimization-tasks/1', (route) => {
      expect(route.request().method()).toBe('DELETE')
      return route.fulfill(response(null))
    })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/app')

    const recent = page.locator('.recent-task').first()
    await recent.getByRole('button', { name: /删除岗位优化记录/ }).click()
    await expect(page.getByRole('dialog')).toContainText('原始简历不会被删除')
    await page.getByRole('button', { name: '删除记录', exact: true }).click()
    await expect(page.locator('.recent-task')).toHaveCount(0)
  })

  test('shows the optional insight link only when the server reports a cohort', async ({
    page,
  }) => {
    await mockHome(page, [readyResume], { cohorts: [{}] })
    await page.goto('/app')
    await expect(page.getByRole('link', { name: /查看洞察/ })).toBeVisible()
  })

  test('uses a single-column layout without horizontal overflow on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await mockHome(page)
    await page.goto('/app')
    await expect(page.locator('.home-target-column')).toBeVisible()

    const layout = await page.evaluate(() => {
      const source = document.querySelector('.home-source-column')?.getBoundingClientRect()
      const target = document.querySelector('.home-target-column')?.getBoundingClientRect()
      return {
        sourceTop: source?.top ?? 0,
        targetTop: target?.top ?? 0,
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }
    })
    expect(layout.targetTop).toBeGreaterThan(layout.sourceTop)
    expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth)
  })
})
