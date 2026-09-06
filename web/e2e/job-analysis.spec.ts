import { expect, test, type Page } from '@playwright/test'

const response = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data }),
})

const businessError = (message: string) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 500, message, data: null }),
})

const longEvidence = '负责平台服务与数据链路建设，围绕稳定性、性能和可观测性持续改进。'.repeat(60)

const canonicalSource = {
  schemaVersion: 'RESUME_DOCUMENT_V1',
  basics: {
    name: '林然',
    jobIntention: 'Java 后端工程师',
    highestEducation: null,
    contacts: [{ id: 'email-1', type: 'EMAIL', label: null, value: 'linran@example.com' }],
  },
  sections: [{
    id: 'experience', kind: 'EXPERIENCE', title: '工作经历', entries: [{
      id: 'entry-1', organization: '某科技公司', role: '后端工程师', school: null, degree: null,
      major: null, startDate: '2021', endDate: '2024', location: null, group: null, skillItems: null,
      bullets: [
        { id: 'bullet-1', text: '负责 Java 后端服务开发与维护' },
        ...Array.from({ length: 18 }, (_, index) => ({
          id: `bullet-${index + 2}`,
          text: `持续建设服务稳定性与交付流程 ${index + 2}`,
        })),
      ],
    }],
  }],
}

const makeRequirement = (index: number, matchLevel: string = 'MATCHED') => ({
  evidenceRequirementId: index,
  requirementText: `岗位要求 ${index}：具备 Java 后端与系统设计能力`,
  importance: index % 2 ? 'REQUIRED' : 'BONUS',
  matchLevel,
  conclusion: matchLevel === 'NO_EVIDENCE' ? '当前材料未找到支持该要求的证据。' : '当前材料存在相关证据。',
  suggestion: matchLevel === 'NO_EVIDENCE' ? '如确有真实经历，请手动补充。' : '核对已有表达。',
  evidences: matchLevel === 'NO_EVIDENCE'
    ? []
    : [{
        requirementEvidenceId: index * 10,
        sectionLabel: '工作经历',
        evidenceText: index === 2 ? longEvidence : `材料引用 ${index}`,
        supportLevel: matchLevel === 'MATCHED' ? 'SUFFICIENT' : 'PARTIAL',
      }],
})

const evidenceResult = (levels: string[] = ['MATCHED', 'PARTIAL_EVIDENCE', 'NO_EVIDENCE', 'MATCHED', 'MATCHED', 'MATCHED', 'MATCHED', 'MATCHED', 'MATCHED']) => ({
  optimizationTaskId: 42,
  sourceResumeVersionId: 1,
  targetResumeVersionId: 2,
  jobTargetId: 3,
  status: 'SUCCESS',
  jobTitle: 'Java 后端工程师',
  resumeName: '我的简历',
  analysisMode: 'EVIDENCE',
  sourceCanonicalDocument: JSON.stringify(canonicalSource),
  evidenceAnalysis: {
    evidenceAnalysisId: 7,
    matchedCount: levels.filter((level) => level === 'MATCHED').length,
    partialEvidenceCount: levels.filter((level) => level === 'PARTIAL_EVIDENCE').length,
    noEvidenceCount: levels.filter((level) => level === 'NO_EVIDENCE').length,
    requirements: levels.map((level, index) => makeRequirement(index + 1, level)),
  },
  legacyAnalysis: null,
})

const legacyResult = {
  optimizationTaskId: 42,
  sourceResumeVersionId: 1,
  targetResumeVersionId: 2,
  jobTargetId: 3,
  status: 'SUCCESS',
  jobTitle: 'Java 后端工程师',
  resumeName: '我的简历',
  analysisMode: 'LEGACY_COMPAT',
  evidenceAnalysis: null,
  legacyAnalysis: {
    strongMatches: Array.from({ length: 5 }, (_, index) => ({ item: `已有能力 ${index + 1}`, reason: longEvidence })),
    weakMatches: Array.from({ length: 6 }, (_, index) => ({ item: `待完善要求 ${index + 1}`, reason: longEvidence })),
    weakExperienceDescriptions: [],
    missingSkills: Array.from({ length: 5 }, (_, index) => ({ item: `未体现技能 ${index + 1}`, reason: longEvidence })),
  },
}

const user = {
  id: 1,
  username: 'analysis-test',
  email: 'analysis@example.invalid',
  nickname: '岗位分析测试用户',
  createdAt: '2026-01-01T00:00:00Z',
}

async function mockShell(page: Page, result: unknown, options: { delay?: number } = {}) {
  await page.addInitScript(() => {
    window.localStorage.setItem('ai-resume-token', 'job-analysis-test-token')
    ;(window as Window & { __jobAnalysisWheelListeners?: number }).__jobAnalysisWheelListeners = 0
    const originalAddEventListener = window.addEventListener.bind(window)
    window.addEventListener = ((type: string, ...args: Parameters<Window['addEventListener']>) => {
      if (type === 'wheel') {
        const state = window as Window & { __jobAnalysisWheelListeners?: number }
        state.__jobAnalysisWheelListeners = (state.__jobAnalysisWheelListeners ?? 0) + 1
      }
      return originalAddEventListener(type, ...args)
    }) as Window['addEventListener']
  })
  await page.route('**/api/users/me', (route) => route.fulfill(response(user)))
  await page.route('**/api/optimization-tasks/42/analysis-result', async (route) => {
    if (options.delay) await new Promise((resolve) => setTimeout(resolve, options.delay))
    await route.fulfill(response(result))
  })
}

async function openAnalysis(page: Page, result: unknown = evidenceResult()) {
  await mockShell(page, result)
  await page.goto('/job-analysis/42')
}

async function openSourcePreview(page: Page, result: unknown, source = canonicalSource) {
  const resultWithSource = {
    ...(result as Record<string, unknown>),
    sourceCanonicalDocument: JSON.stringify(source),
  }
  await openAnalysis(page, resultWithSource)
  await page.route('**/api/resumes/1/review', (route) => route.fulfill(response({
    resumeId: 1,
    qualityStatus: 'READY',
    qualityIssues: null,
    unresolvedItems: '[]',
    canonicalDocument: JSON.stringify(source),
  })))
  await page.reload()
}

async function pageMetrics(page: Page) {
  return page.evaluate(() => {
    const appPage = document.querySelector<HTMLElement>('.app-page')
    const taskPage = document.querySelector<HTMLElement>('.analysis-task-page')
    const content = document.querySelector<HTMLElement>('.analysis-task-content')
    const layout = document.querySelector<HTMLElement>('.analysis-review-layout, .analysis-legacy-layout')
    const list = document.querySelector<HTMLElement>('.requirement-list')
    const detail = document.querySelector<HTMLElement>('.analysis-evidence-detail, .legacy-detail-list')
    const rect = (element: HTMLElement | null) => {
      const box = element?.getBoundingClientRect()
      return box ? { top: box.top, bottom: box.bottom, height: box.height } : null
    }
    return {
      appPage: appPage ? { scrollHeight: appPage.scrollHeight, clientHeight: appPage.clientHeight, scrollTop: appPage.scrollTop } : null,
      taskPage: rect(taskPage),
      content: rect(content),
      layout: rect(layout),
      list: list ? { scrollHeight: list.scrollHeight, clientHeight: list.clientHeight, scrollWidth: list.scrollWidth, clientWidth: list.clientWidth } : null,
      detail: detail ? { scrollHeight: detail.scrollHeight, clientHeight: detail.clientHeight, scrollTop: detail.scrollTop } : null,
    }
  })
}

async function expectFixedViewport(page: Page, requireLayout = true) {
  const metrics = await pageMetrics(page)
  expect(metrics.appPage).not.toBeNull()
  expect(metrics.appPage!.scrollHeight).toBeLessThanOrEqual(metrics.appPage!.clientHeight + 1)
  expect(metrics.appPage!.scrollTop).toBe(0)
  expect(metrics.taskPage!.top).toBeGreaterThanOrEqual(0)
  expect(metrics.taskPage!.bottom).toBeLessThanOrEqual(metrics.appPage!.clientHeight + 1)
  if (requireLayout) expect(metrics.layout!.height).toBeGreaterThan(0)
}

const browserIssues = new WeakMap<Page, string[]>()

test.beforeEach(({ page }) => {
  const issues: string[] = []
  browserIssues.set(page, issues)
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') issues.push(`${message.type()}: ${message.text()}`)
  })
  page.on('pageerror', (error) => issues.push(`pageerror: ${error.message}`))
})

test.afterEach(({ page }) => {
  expect(browserIssues.get(page) ?? []).toEqual([])
})

test.describe('Job Analysis fixed evidence workspace', () => {
  for (const viewport of [
    { width: 1920, height: 1080 },
    { width: 1440, height: 900 },
    { width: 1366, height: 768 },
    { width: 1280, height: 720 },
    { width: 1024, height: 768 },
  ]) {
    test(`keeps the evidence workspace inside the viewport at ${viewport.width}x${viewport.height}`, async ({ page }) => {
      await page.setViewportSize(viewport)
      await openAnalysis(page)
      await expect(page.locator('.analysis-review-layout')).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Java 后端工程师' })).toBeVisible()
      await expect(page.getByText('岗位要求 9：具备 Java 后端与系统设计能力', { exact: true })).toBeVisible()
      await page.locator('.requirement-item').nth(1).click()
      await expectFixedViewport(page)
      expect(await page.evaluate(() => (window as Window & { __jobAnalysisWheelListeners?: number }).__jobAnalysisWheelListeners)).toBe(0)
      const metrics = await pageMetrics(page)
      expect(metrics.detail!.clientHeight).toBeGreaterThan(0)
    })
  }

  test('assigns scrolling to the requirement rail and frozen SOURCE canvas without scroll chaining', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await openAnalysis(page, evidenceResult(Array.from({ length: 18 }, () => 'MATCHED')))
    await expect(page.locator('.analysis-review-layout')).toBeVisible()
    const list = page.locator('.requirement-list')
    const source = page.locator('.source-preview-scroll')

    const before = await pageMetrics(page)
    await page.mouse.move(8, 760)
    await page.mouse.wheel(0, 600)
    expect((await pageMetrics(page)).appPage!.scrollTop).toBe(before.appPage!.scrollTop)

    await list.evaluate((element) => { element.scrollTop = 0 })
    await list.hover()
    await page.mouse.wheel(0, 500)
    await expect.poll(async () => (await list.evaluate((element) => element.scrollTop))).toBeGreaterThan(0)
    expect((await pageMetrics(page)).appPage!.scrollTop).toBe(0)

    await source.evaluate((element) => { element.scrollTop = 0 })
    await source.hover()
    await page.mouse.wheel(0, 700)
    await expect.poll(async () => (await source.evaluate((element) => element.scrollTop))).toBeGreaterThan(0)
    expect((await pageMetrics(page)).appPage!.scrollTop).toBe(0)

    await list.evaluate((element) => { element.scrollTop = element.scrollHeight })
    await list.hover()
    await page.mouse.wheel(0, 900)
    expect((await pageMetrics(page)).appPage!.scrollTop).toBe(0)

    await source.evaluate((element) => { element.scrollTop = element.scrollHeight })
    await source.hover()
    await page.mouse.wheel(0, 900)
    expect((await pageMetrics(page)).appPage!.scrollTop).toBe(0)
  })

  test('resets detail scroll position when selecting another requirement and preserves the workspace route query', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await openAnalysis(page)
    await expect(page.locator('.analysis-review-layout')).toBeVisible()
    const selectedBar = page.locator('.analysis-selected-requirement-bar')
    await page.locator('.requirement-item').nth(1).click()
    await expect(selectedBar).toContainText('要求 02 /')
    await page.locator('.requirement-item').nth(3).click()
    await expect(selectedBar).toContainText('要求 04 /')
    await page.route('**/api/workspace/**', (route) => route.fulfill(response({})))
    await page.getByRole('button', { name: '修改简历', exact: true }).click()
    await expect(page).toHaveURL('/workspace/42?requirement=4')
  })

  test('links matched evidence to the canonical SOURCE bullet without guessing', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    const result = { ...evidenceResult(['MATCHED']), resumeId: 1 }
    const evidence = result.evidenceAnalysis.requirements[0]?.evidences[0]
    if (evidence) evidence.evidenceText = '负责 Java 后端服务开发与维护'
    await openSourcePreview(page, result)
    await expect(page.locator('.source-preview-section.is-focused')).toBeVisible()
    await expect(page.locator('.source-preview-entry mark')).toHaveText('负责 Java 后端服务开发与维护')
    await expectFixedViewport(page)
  })

  test('uses the task-frozen SOURCE instead of the resume current canonical document', async ({ page }) => {
    const result = { ...evidenceResult(['MATCHED']), resumeId: 1 }
    const evidence = result.evidenceAnalysis.requirements[0]?.evidences[0]
    if (evidence) evidence.evidenceText = '负责 Java 后端服务开发与维护'
    const currentSource = JSON.parse(JSON.stringify(canonicalSource)) as typeof canonicalSource
    currentSource.sections[0]!.entries[0]!.bullets[0]!.text = '后续 reparse 得到的 SOURCE B'
    let currentReviewRequested = false

    await mockShell(page, result)
    await page.route('**/api/resumes/1/review', async (route) => {
      currentReviewRequested = true
      await route.fulfill(response({
        resumeId: 1,
        qualityStatus: 'READY',
        qualityIssues: null,
        unresolvedItems: '[]',
        canonicalDocument: JSON.stringify(currentSource),
      }))
    })
    await page.goto('/job-analysis/42')

    await expect(page.locator('.source-preview-entry mark')).toHaveText('负责 Java 后端服务开发与维护')
    expect(currentReviewRequested).toBe(false)
  })

  test('fails closed when a quote matches multiple SOURCE bullets', async ({ page }) => {
    const result = { ...evidenceResult(['MATCHED']), resumeId: 1 }
    const evidence = result.evidenceAnalysis.requirements[0]?.evidences[0]
    if (evidence) evidence.evidenceText = '重复证据'
    const source = JSON.parse(JSON.stringify(canonicalSource)) as typeof canonicalSource
    const entry = source.sections[0]!.entries[0]!
    entry.bullets = [{ id: 'bullet-a', text: '重复证据' }, { id: 'bullet-b', text: '重复证据' }]

    await openSourcePreview(page, result, source)
    await expect(page.locator('.source-preview-entry mark')).toHaveCount(0)
  })

  test('does not search other sections when sectionLabel cannot be resolved', async ({ page }) => {
    const result = { ...evidenceResult(['MATCHED']), resumeId: 1 }
    const evidence = result.evidenceAnalysis.requirements[0]?.evidences[0]
    if (evidence) {
      evidence.sectionLabel = '不存在的章节'
      evidence.evidenceText = '跨章节证据'
    }
    const source = JSON.parse(JSON.stringify(canonicalSource)) as typeof canonicalSource
    source.sections.push({
      id: 'projects', kind: 'PROJECT', title: '项目经历', entries: [{
        id: 'project-1', organization: null, role: null, school: null, degree: null, major: null,
        startDate: null, endDate: null, location: null, group: null, skillItems: null,
        bullets: [{ id: 'project-bullet', text: '跨章节证据' }],
      }],
    })

    await openSourcePreview(page, result, source)
    await expect(page.locator('.source-preview-entry mark')).toHaveCount(0)
    await expect(page.locator('.source-preview-section.is-focused')).toHaveCount(0)
  })

  test('does not create a SOURCE anchor for NO_EVIDENCE', async ({ page }) => {
    await openSourcePreview(page, { ...evidenceResult(['NO_EVIDENCE']), resumeId: 1 })
    await expect(page.locator('.source-preview-entry mark')).toHaveCount(0)
    await expect(page.locator('.source-preview-section.is-focused')).toHaveCount(0)
  })

  test('keeps matched, partial and missing summaries inside the fixed frame', async ({ page }) => {
    for (const levels of [
      ['MATCHED', 'MATCHED', 'MATCHED'],
      ['PARTIAL_EVIDENCE', 'PARTIAL_EVIDENCE', 'MATCHED'],
      ['NO_EVIDENCE', 'NO_EVIDENCE', 'MATCHED'],
    ]) {
      await page.setViewportSize({ width: 1366, height: 768 })
      await openAnalysis(page, evidenceResult(levels))
      await expect(page.locator('.analysis-review-layout')).toBeVisible()
      await expectFixedViewport(page)
    }
  })

  test('keeps loading, error and empty states within the remaining height', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    await mockShell(page, evidenceResult(), { delay: 1200 })
    await page.goto('/job-analysis/42')
    await expect(page.locator('.ui-skeleton-block')).toBeVisible()
    await expectFixedViewport(page, false)

    await page.goto('/job-analysis/42?error=1')
    await page.unroute('**/api/optimization-tasks/42/analysis-result')
    await page.route('**/api/optimization-tasks/42/analysis-result', (route) => route.fulfill(businessError('分析结果暂时不可用')))
    await page.reload()
    await expect(page.getByText('暂时无法查看分析结果', { exact: true })).toBeVisible()
    await expectFixedViewport(page, false)

    await page.unroute('**/api/optimization-tasks/42/analysis-result')
    await page.route('**/api/optimization-tasks/42/analysis-result', (route) => route.fulfill(response(null)))
    await page.reload()
    await expect(page.getByText('分析结果尚未生成', { exact: true })).toBeVisible()
    await expectFixedViewport(page, false)
  })

  test('keeps legacy detail content internally scrollable', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await openAnalysis(page, legacyResult)
    await expect(page.locator('.analysis-legacy-layout')).toBeVisible()
    await expectFixedViewport(page)
    const legacyDetail = page.locator('.legacy-detail-list')
    expect(await legacyDetail.evaluate((element) => element.scrollHeight)).toBeGreaterThan(await legacyDetail.evaluate((element) => element.clientHeight))
    await legacyDetail.evaluate((element) => { element.scrollTop = element.scrollHeight })
    await legacyDetail.hover()
    await page.mouse.wheel(0, 900)
    expect((await pageMetrics(page)).appPage!.scrollTop).toBe(0)
  })

  test('uses a compact fixed two-zone workspace on mobile without page overflow', async ({ page }) => {
    for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 1024 }]) {
      await page.setViewportSize(viewport)
      await openAnalysis(page)
      await expect(page.locator('.analysis-review-layout')).toBeVisible()
      await page.locator('.requirement-item').nth(1).click()
      await expectFixedViewport(page)
      const metrics = await pageMetrics(page)
      expect(metrics.detail!.clientHeight).toBeGreaterThan(0)
      await expect(page.locator('.analysis-evidence-detail')).toHaveAttribute('aria-label', '当前岗位要求的证据详情，可滚动')
      const source = page.locator('.source-preview-scroll')
      await source.evaluate((element) => { element.scrollTop = 0 })
      await source.hover()
      await page.mouse.wheel(0, 700)
      await expect.poll(async () => (await source.evaluate((element) => element.scrollTop))).toBeGreaterThan(0)
    }
  })

  test('does not change ordinary page scrolling after leaving analysis', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await openAnalysis(page)
    await expect(page.locator('.analysis-review-layout')).toBeVisible()
    await page.route('**/api/resumes', (route) => route.fulfill(response([])))
    await page.route('**/api/job-direction-insights', (route) => route.fulfill(response({ cohorts: [] })))
    await page.route('**/api/optimization-tasks/recent*', (route) => route.fulfill(response([])))
    await page.goto('/app')
    await expect(page.getByRole('heading', { name: '针对一个岗位优化简历' })).toBeVisible()
    expect(await page.locator('.app-page').evaluate((element) => getComputedStyle(element).overflowY)).toBe('auto')
  })
})
