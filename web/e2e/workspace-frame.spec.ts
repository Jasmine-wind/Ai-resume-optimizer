import { expect, test, type Page } from '@playwright/test'

const response = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data }),
})

const document = {
  schemaVersion: 'RESUME_DOCUMENT_V1',
  basics: {
    name: 'Alex Chen',
    jobIntention: 'Java 后端工程师',
    highestEducation: '硕士',
    contacts: [{ id: 'email-1', type: 'EMAIL', label: null, value: 'alex@example.com' }],
  },
  sections: [
    {
      id: 'experience',
      kind: 'EXPERIENCE',
      title: '工作经历',
      entries: [
        {
          id: 'exp-1',
          organization: '某科技公司',
          role: '后端工程师',
          school: null,
          degree: null,
          major: null,
          startDate: '2021',
          endDate: '2024',
          location: null,
          group: null,
          skillItems: null,
          bullets: Array.from({ length: 12 }, (_, index) => ({
            id: `bullet-${index}`,
            text: `负责 Java 后端服务与 Redis 缓存优化工作 ${index + 1}`,
          })),
        },
      ],
    },
  ],
}

const requirement = (id: number, level: string) => ({
  evidenceRequirementId: id,
  requirementText: id === 3 ? '具备 Redis 缓存设计经验' : `岗位要求 ${id}`,
  importance: 'REQUIRED',
  matchLevel: level,
  conclusion:
    level === 'PARTIAL_EVIDENCE' ? '存在相关材料，但当前表达还不完整。' : '当前材料已有支持。',
  suggestion: '建议核对真实使用场景并完善表达。',
  evidences: [
    {
      requirementEvidenceId: id * 10,
      sectionLabel: '工作经历',
      evidenceText:
        id === 3
          ? '负责 Java 后端服务与 Redis 缓存优化工作 1'
          : id === 4
            ? '负责 Java 后端服务与 Redis 缓存优化工作 2'
            : `简历中的证据 ${id}`,
      supportLevel: level === 'MATCHED' ? 'SUFFICIENT' : 'PARTIAL',
    },
  ],
})

const longDocument = {
  ...document,
  basics: {
    ...document.basics,
    name: '张晓测试与国际化交付团队长期协作负责人',
    contacts: Array.from({ length: 20 }, (_, index) => ({
      id: `contact-${index}`,
      type: index === 0 ? 'EMAIL' : index === 1 ? 'GITHUB' : index === 2 ? 'WEBSITE' : 'OTHER',
      label: null,
      value:
        index === 0
          ? 'long.resume.editor@example.com'
          : `https://example.com/profiles/very-long-professional-address-${index}/中英文混排`,
    })),
  },
  sections: [
    {
      id: 'experience',
      kind: 'EXPERIENCE',
      title: '工作经历',
      entries: Array.from({ length: 5 }, (_, entryIndex) => ({
        id: `long-entry-${entryIndex}`,
        organization: `某国际化云原生基础设施与企业数字化解决方案科技有限公司第 ${entryIndex + 1} 事业部`,
        role: '高级后端平台工程师兼跨区域可靠性交付负责人',
        school: null,
        degree: null,
        major: null,
        startDate: '2020.01',
        endDate: '2026.12 至今',
        location: '上海 · 新加坡 · Remote',
        group: null,
        skillItems: null,
        bullets: Array.from({ length: 6 }, (_, bulletIndex) => {
          const position = entryIndex * 6 + bulletIndex
          return {
            id: `bullet-${position}`,
            text:
              position < 2
                ? `负责 Java 后端服务与 Redis 缓存优化工作 ${position + 1}`
                : position === 2
                  ? `负责中英文混排 delivery、稳定性治理与 URL https://example.com/runbook，${'持续验证真实数据。'.repeat(240)}`
                  : `负责第 ${position + 1} 项真实平台工程交付，覆盖 API、可观测性、容量规划与跨团队协作。`,
          }
        }),
      })),
    },
    ...Array.from({ length: 9 }, (_, sectionIndex) => ({
      id: `section-${sectionIndex}`,
      kind: 'OTHER',
      title: `专业经历补充章节 ${sectionIndex + 1}`,
      entries: [
        {
          id: `other-entry-${sectionIndex}`,
          organization: `长期项目与专业实践 ${sectionIndex + 1}`,
          role: null,
          school: null,
          degree: null,
          major: null,
          startDate: null,
          endDate: null,
          location: null,
          group: null,
          skillItems: null,
          bullets: [{ id: `other-bullet-${sectionIndex}`, text: `补充材料 ${sectionIndex + 1}` }],
        },
      ],
    })),
  ],
}

const analysis = {
  optimizationTaskId: 42,
  sourceResumeVersionId: 1,
  targetResumeVersionId: 2,
  jobTargetId: 3,
  status: 'SUCCESS',
  jobTitle: 'Java 后端工程师',
  resumeName: 'chinese-java-two-page.pdf',
  analysisMode: 'EVIDENCE',
  evidenceAnalysis: {
    evidenceAnalysisId: 7,
    matchedCount: 7,
    partialEvidenceCount: 1,
    noEvidenceCount: 1,
    requirements: Array.from({ length: 9 }, (_, index) =>
      requirement(index + 1, index === 2 ? 'PARTIAL_EVIDENCE' : 'MATCHED'),
    ),
  },
  legacyAnalysis: null,
}

async function mockWorkspace(
  page: Page,
  options: {
    failSaveOnce?: boolean
    denseRequirements?: boolean
    longResume?: boolean
    lowValueSuggestion?: boolean
    reviewSuggestion?: boolean
  } = {},
) {
  await page.addInitScript(() => localStorage.setItem('ai-resume-token', 'workspace-frame-token'))
  await page.route('**/api/users/me', (route) =>
    route.fulfill(
      response({
        id: 1,
        username: 'workspace',
        email: 'workspace@example.invalid',
        nickname: '工作区测试用户',
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ),
  )
  let saveAttempts = 0
  await page.route('**/api/workspace/42/content', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill(
        response({
          optimizationTaskId: 42,
          revision: 3,
          document: options.longResume ? longDocument : document,
        }),
      )
    }
    saveAttempts += 1
    if (options.failSaveOnce && saveAttempts === 1) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, message: '保存服务暂时不可用', data: null }),
      })
    }
    return route.fulfill(
      response({ saved: true, conflict: false, revision: 3 + saveAttempts, document: null }),
    )
  })
  const mockedRequirements = Array.from(
    { length: options.denseRequirements ? 18 : 9 },
    (_, index) => requirement(index + 1, index === 2 ? 'PARTIAL_EVIDENCE' : 'MATCHED'),
  )
  if (options.longResume) {
    const sectionEvidence = mockedRequirements[3]?.evidences[0]
    if (sectionEvidence) {
      sectionEvidence.sectionLabel = '专业经历补充章节 1'
      sectionEvidence.evidenceText = '补充材料 1'
    }
  }
  const mockedAnalysis =
    options.denseRequirements || options.longResume
      ? {
          ...analysis,
          evidenceAnalysis: {
            ...analysis.evidenceAnalysis,
            requirements: mockedRequirements,
          },
        }
      : analysis
  await page.route('**/api/optimization-tasks/42/analysis-result', (route) =>
    route.fulfill(response(mockedAnalysis)),
  )
  await page.route('**/api/workspace/42/artifacts', (route) => route.fulfill(response([])))
  await page.route('**/api/workspace/42/bullet-suggestion', async (route) => {
    const body = route.request().postDataJSON() as {
      requestId: string
      bulletId: string
      baseRevision: number
      originalText: string
    }
    await route.fulfill(
      response({
        requestId: body.requestId,
        bulletId: body.bulletId,
        baseRevision: body.baseRevision,
        state: options.lowValueSuggestion ? 'REJECTED' : 'READY',
        originalText: body.originalText,
        suggestedText: options.lowValueSuggestion
          ? null
          : options.reviewSuggestion
            ? '负责订单服务开发，并使用 Kafka 处理异步消息'
            : `${body.originalText}，持续改善交付稳定性`,
        reason: options.lowValueSuggestion
          ? null
          : options.reviewSuggestion
            ? '补充 Kafka 相关技术描述；原文未包含该信息，请确认真实性。'
            : '保留真实事实，只让职责与结果更清楚。',
        reviewCode: options.reviewSuggestion ? 'NEW_TECHNOLOGY' : null,
        reviewMessage: options.reviewSuggestion
          ? '建议包含原文未写明的信息，请确认这些内容确实属于你的真实经历。'
          : null,
        rejectCode: options.lowValueSuggestion ? 'LOW_VALUE_CHANGE' : null,
        rejectMessage: options.lowValueSuggestion
          ? '这次改写只产生了很轻微的表达变化，没有足够价值，建议保留原文。'
          : null,
        modelName: 'workspace-frame-model',
      }),
    )
  })
}

const issues = new WeakMap<Page, string[]>()
test.beforeEach(({ page }) => {
  const messages: string[] = []
  issues.set(page, messages)
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning')
      messages.push(`${message.type()}: ${message.text()}`)
  })
  page.on('pageerror', (error) => messages.push(`pageerror: ${error.message}`))
})
test.afterEach(({ page }) => expect(issues.get(page) ?? []).toEqual([]))

test.describe('Workspace editor frame', () => {
  for (const viewport of [
    { width: 1366, height: 768 },
    { width: 1920, height: 1080 },
  ]) {
    test(`keeps the resume-first two-pane layout at ${viewport.width}x${viewport.height}`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await mockWorkspace(page)
      await page.goto('/workspace/42?requirement=3')
      await expect(page.locator('.workspace-layout')).toBeVisible()
      await expect(page.getByText('岗位定向编辑', { exact: true })).toBeHidden()
      await expect(
        page.getByText('当前优化：具备 Redis 缓存设计经验', { exact: true }),
      ).toBeHidden()
      await expect(page.locator('.workspace-context-strip')).toHaveCount(0)
      await expect(page.getByText('简历正文', { exact: true })).toBeVisible()
      await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible()
      await expect(page.locator('.workspace-inspector')).toBeHidden()
      await expect(page.locator('.workspace-more')).toHaveCount(0)
      await expect(page.locator('.entry-more, .bullet-more, .basics-more')).toHaveCount(0)
      await expect(page.getByRole('button', { name: '恢复版本', exact: true })).toBeVisible()
      await expect(page.getByText('补充信息', { exact: true })).toBeVisible()
      await page.locator('.bullet-line').first().hover()
      await expect(page.getByRole('button', { name: 'AI 优化', exact: true }).first()).toBeVisible()
      await expect(
        page.getByRole('button', { name: '删除某科技公司中的第 1 条工作要点', exact: true }),
      ).toBeVisible()
      await page.locator('.editor-entry').first().hover()
      await expect(
        page.getByRole('button', { name: '删除条目：某科技公司', exact: true }),
      ).toBeVisible()
      const sectionFooter = page.locator('[data-section-id="experience"] .section-footer')
      await page.mouse.move(0, 0)
      await expect(sectionFooter).toHaveCSS('opacity', '0')
      await page.locator('[data-section-id="experience"]').hover()
      await expect(sectionFooter).toHaveCSS('opacity', '1')
      await expect(page.locator('.editor-section.is-focused')).toBeVisible()
      await expect(page.locator('.bullet-block.is-evidence-focus')).toHaveCount(1)
      const section = page.locator('.editor-section').first()
      const sectionToggle = section.locator('.section-collapse-toggle')
      const sectionContentId = await sectionToggle.getAttribute('aria-controls')
      expect(sectionContentId).toBeTruthy()
      await sectionToggle.focus()
      await page.keyboard.press('Enter')
      await expect(sectionToggle).toHaveAttribute('aria-expanded', 'false')
      await expect(section.locator('.editor-section-content')).toBeHidden()
      await page.keyboard.press('Space')
      await expect(sectionToggle).toBeFocused()
      await expect(sectionToggle).toHaveAttribute('aria-expanded', 'true')
      await expect(page.locator(`#${sectionContentId}`)).toBeVisible()

      const metrics = await page.evaluate(() => {
        const app = document.querySelector<HTMLElement>('.app-page')!
        const layout = document.querySelector<HTMLElement>('.workspace-layout')!
        const columns = Array.from(layout.children).map(
          (child) => child.getBoundingClientRect().width,
        )
        return {
          appScrollHeight: app.scrollHeight,
          appClientHeight: app.clientHeight,
          appScrollTop: app.scrollTop,
          columns,
        }
      })
      expect(metrics.appScrollHeight).toBeLessThanOrEqual(metrics.appClientHeight + 1)
      expect(metrics.appScrollTop).toBe(0)
      expect(metrics.columns).toHaveLength(2)
      expect(metrics.columns[1]).toBeGreaterThan(metrics.columns[0]! * 2.5)
    })
  }

  test('keeps non-empty section add action discoverable for coarse pointers', async ({
    browser,
  }) => {
    const context = await browser.newContext({
      baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173',
      hasTouch: true,
      viewport: { width: 1024, height: 900 },
    })
    const page = await context.newPage()
    try {
      await mockWorkspace(page)
      await page.goto('/workspace/42?requirement=3')
      const footer = page.locator('[data-section-id="experience"] .section-footer')
      await expect(footer).toHaveCSS('opacity', '1')
      await expect(footer).toHaveCSS('pointer-events', 'auto')
      await expect(footer.getByRole('button', { name: '添加工作经历' })).toBeVisible()
    } finally {
      await context.close()
    }
  })

  test('keeps requirements and document scrollable while inspector stays concise', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await mockWorkspace(page, { denseRequirements: true })
    await page.goto('/workspace/42?requirement=3')
    const app = page.locator('.app-page')
    const requirements = page.locator('.requirement-list')
    const editor = page.locator('.resume-stage-scroll')
    await requirements.hover()
    await page.mouse.wheel(0, 600)
    await expect
      .poll(() => requirements.evaluate((element) => element.scrollTop))
      .toBeGreaterThan(0)
    await editor.hover()
    await page.mouse.wheel(0, 600)
    await expect.poll(() => editor.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
    await page.getByRole('button', { name: /分析详情/ }).click()
    const context = page.locator('.inspector-scroll')
    await expect(page.locator('.workspace-inspector')).toBeVisible()
    await expect(page.getByRole('button', { name: '收起要求检查器' })).toHaveCount(1)
    await expect(page.locator('.inspector-footer')).toHaveCount(0)
    const inspectorMetrics = await context.evaluate((element) => ({
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
    }))
    expect(inspectorMetrics.scrollHeight).toBeGreaterThanOrEqual(inspectorMetrics.clientHeight)
    expect(await app.evaluate((element) => element.scrollTop)).toBe(0)
  })

  test('opens the AI inspector for one bullet and keeps the suggestion lifecycle controls together', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await mockWorkspace(page)
    await page.goto('/workspace/42?requirement=3')
    await page.locator('.bullet-line').first().hover()
    await page.getByRole('button', { name: 'AI 优化', exact: true }).first().click()
    await page.getByRole('menuitem', { name: '精简' }).click()

    await expect(page.locator('.workspace-inspector')).toBeVisible()
    await expect(page.locator('.workspace-layout > .workspace-requirements')).toBeHidden()
    await expect(page.locator('.resume-stage .toolbar-button-accent')).toBeHidden()
    await expect(page.getByText('原文', { exact: true })).toBeVisible()
    await expect(page.getByText('建议版本', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '采纳' })).toBeVisible()
    await expect(page.getByRole('button', { name: '重新生成' })).toBeVisible()
    await expect(page.getByRole('button', { name: '拒绝' })).toBeVisible()

    await page.getByRole('button', { name: '拒绝' }).click()
    await expect(page.getByText('建议版本', { exact: true })).toBeHidden()
  })

  test('keeps a fact review advisory applyable and undoable', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await mockWorkspace(page, { reviewSuggestion: true })
    await page.goto('/workspace/42?requirement=3')
    const bullet = page.locator('[data-bullet-id="bullet-0"]')
    await bullet.hover()
    await bullet.getByRole('button', { name: 'AI 优化', exact: true }).click()
    await page.getByRole('menuitem', { name: '精简' }).click()

    await expect(page.getByText('建议版本', { exact: true })).toBeVisible()
    await expect(page.getByText('负责订单服务开发，并使用 Kafka 处理异步消息', { exact: true })).toBeVisible()
    await expect(page.getByText('请确认内容真实', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '确认并采纳', exact: true })).toBeVisible()
    await expect(page.getByText('没有通过事实校验', { exact: true })).toHaveCount(0)

    await page.getByRole('button', { name: '确认并采纳', exact: true }).click()
    await expect(bullet.locator('textarea')).toHaveValue('负责订单服务开发，并使用 Kafka 处理异步消息')
    await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await expect(bullet.locator('textarea')).toHaveValue('负责 Java 后端服务与 Redis 缓存优化工作 1')
  })

  test('shows a neutral no-op state for a low-value AI rewrite', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await mockWorkspace(page, { lowValueSuggestion: true })
    await page.goto('/workspace/42?requirement=3')
    await page.locator('.bullet-line').first().hover()
    await page.getByRole('button', { name: 'AI 优化', exact: true }).first().click()
    await page.getByRole('menuitem', { name: '精简' }).click()

    await expect(page.getByText('这条内容暂时不需要改', { exact: true })).toBeVisible()
    await expect(page.getByText('这次生成只产生了很轻微的表达变化，没有足够价值，已保留原文。', { exact: true })).toBeVisible()
    await expect(page.getByText('原因：原文已经比较清楚', { exact: true })).toBeVisible()
    await expect(page.getByText('建议版本', { exact: true })).toBeHidden()
    await expect(page.getByRole('button', { name: '采纳', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '换个方向', exact: true })).toBeVisible()
  })

  test('keeps generic add and delete actions visually aligned on desktop and mobile', async ({
    page,
  }) => {
    await mockWorkspace(page, { longResume: true })
    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(viewport)
      await page.goto('/workspace/42?requirement=3')
      const entry = page.locator('.editor-entry.is-generic').first()
      await entry.hover()
      const add = entry.getByRole('button', { name: '添加内容', exact: true })
      const remove = entry.getByRole('button', { name: /删除专业经历补充章节 1中的这段内容/ })
      await expect(add).toBeVisible()
      await expect(remove).toBeVisible()
      const [addBox, removeBox] = await Promise.all([add.boundingBox(), remove.boundingBox()])
      expect(addBox).not.toBeNull()
      expect(removeBox).not.toBeNull()
      expect(Math.abs((addBox!.y + addBox!.height / 2) - (removeBox!.y + removeBox!.height / 2))).toBeLessThanOrEqual(2)
      const metrics = await page.evaluate(() => ({
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
      }))
      expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth)
    }
  })

  test('deletes a bullet directly and restores it through Undo', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await mockWorkspace(page)
    await page.goto('/workspace/42?requirement=3')

    const bullet = page.locator('[data-bullet-id="bullet-0"]')
    await bullet.hover()
    await bullet
      .getByRole('button', { name: '删除某科技公司中的第 1 条工作要点', exact: true })
      .click()
    await expect(page.locator('[data-bullet-id="bullet-0"]')).toHaveCount(0)
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await expect(page.locator('[data-bullet-id="bullet-0"]')).toHaveCount(1)
  })

  test('confirms entry deletion while keeping direct entry editing available', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await mockWorkspace(page)
    await page.goto('/workspace/42?requirement=3')

    const entry = page.locator('[data-entry-id="exp-1"]')
    await entry.hover()
    await entry.getByRole('button', { name: '删除条目：某科技公司', exact: true }).click()
    await expect(page.getByText('删除这段工作经历？', { exact: true })).toBeVisible()
    await expect(page.getByText('其中的工作要点也会一并删除。', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await expect(entry).toBeVisible()

    await entry.hover()
    await entry.getByRole('button', { name: '删除条目：某科技公司', exact: true }).click()
    await page
      .locator('.el-message-box__btns')
      .getByRole('button', { name: '删除', exact: true })
      .click()
    await expect(page.locator('[data-entry-id="exp-1"]')).toHaveCount(0)
  })

  test('shows save failure recovery without changing the save API', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 768 })
    await mockWorkspace(page, { failSaveOnce: true })
    await page.goto('/workspace/42?requirement=3')
    await page.getByRole('button', { name: '编辑姓名' }).click()
    const name = page.locator('.identity-name-input input')
    await name.fill('Alex Chen Updated')
    await expect(page.getByText('保存失败 · 重新保存', { exact: true })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: '重新保存' })).toBeVisible()
    await page.getByRole('button', { name: '重新保存' }).click()
    await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 5_000 })
  })

  test('keeps long-form editing focused, saved and connected to requirement evidence', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await mockWorkspace(page, { denseRequirements: true, longResume: true })
    await page.goto('/workspace/42?requirement=3')

    const firstEvidence = page.locator('[data-bullet-id="bullet-0"]')
    await expect(firstEvidence).toHaveClass(/is-evidence-focus/)
    await expect(firstEvidence).toBeInViewport()
    await expect(page.getByRole('button', { name: '编辑个人网站' }).first()).toContainText(
      'https://example.com/profiles/',
    )

    const longBullet = page.locator('[data-bullet-id="bullet-2"] textarea')
    await longBullet.fill(
      `负责中英文混排 delivery 与稳定性治理，${'持续验证真实数据。'.repeat(80)}`,
    )
    await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 5_000 })
    await expect(longBullet).toBeFocused()

    const collapsedSectionIds = ['section-0', 'section-1', 'section-2', 'section-3']
    for (const sectionId of collapsedSectionIds) {
      await page.locator(`[data-section-id="${sectionId}"] .section-collapse-toggle`).click()
      await expect(
        page.locator(`[data-section-id="${sectionId}"] .section-collapse-toggle`),
      ).toHaveAttribute('aria-expanded', 'false')
    }

    await page.getByRole('button', { name: /岗位要求 4/ }).click()
    const targetSection = page.locator('[data-section-id="section-0"]')
    const targetEvidence = page.locator('[data-bullet-id="other-bullet-0"]')
    await expect(targetSection.locator('.section-collapse-toggle')).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    await expect(targetEvidence).toHaveClass(/is-evidence-focus/)
    await expect(targetEvidence).toBeInViewport()
    await expect(
      page.locator('[data-section-id="section-1"] .section-collapse-toggle'),
    ).toHaveAttribute('aria-expanded', 'false')

    await page.getByRole('button', { name: '具备 Redis 缓存设计经验' }).click()
    await expect(
      page.locator('[data-section-id="section-0"] .section-collapse-toggle'),
    ).toHaveAttribute('aria-expanded', 'true')
    await expect(
      page.locator('[data-section-id="section-1"] .section-collapse-toggle'),
    ).toHaveAttribute('aria-expanded', 'false')
    const nextEvidence = page.locator('[data-bullet-id="bullet-0"]')
    await expect(nextEvidence).toHaveClass(/is-evidence-focus/)
    await expect(nextEvidence).toBeInViewport()
    const anchoredBullet = nextEvidence.locator('textarea')
    await anchoredBullet.fill('这是一条完全改写后的岗位证据表达，不再包含原始引用。')
    await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 5_000 })
    await expect(nextEvidence).toHaveClass(/is-evidence-focus/)
    await expect(nextEvidence).toBeInViewport()
    await nextEvidence.hover()
    await nextEvidence.getByRole('button', { name: 'AI 优化', exact: true }).click()
    await page.getByRole('menuitem', { name: '精简' }).click()
    await expect(page.getByLabel('AI 优化检查器')).toBeVisible()
    await page.getByRole('button', { name: '收起 AI 优化' }).click()

    const firstEntry = page.locator('[data-entry-id="long-entry-0"]')
    await firstEntry.hover()
    await firstEntry.getByRole('button', { name: '添加工作要点' }).click()
    await expect(firstEntry.locator('textarea').last()).toBeFocused()
    await firstEntry.locator('textarea').last().fill('新增后可以立即输入的工作要点')
    await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 5_000 })

    await page.setViewportSize({ width: 390, height: 844 })
    const editorTab = page.getByRole('tab', { name: '编辑简历' })
    await expect(editorTab).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('tab', { name: '岗位要求' }).click()
    await page.getByRole('button', { name: /岗位要求 4/ }).click()
    await expect(editorTab).toHaveAttribute('aria-selected', 'true')
    await expect(
      page.locator('[data-section-id="section-0"] .section-collapse-toggle'),
    ).toHaveAttribute('aria-expanded', 'true')
    const mobileAnchoredBullet = page.locator('[data-bullet-id="other-bullet-0"]')
    await expect(mobileAnchoredBullet).toHaveClass(/is-evidence-focus/)
    await expect(mobileAnchoredBullet).toBeInViewport()
    await mobileAnchoredBullet.locator('textarea').fill('移动端完全改写后的证据表达。')
    await expect(page.getByText('✓ 已保存', { exact: true })).toBeVisible({ timeout: 5_000 })
    await expect(mobileAnchoredBullet).toHaveClass(/is-evidence-focus/)
    await expect(mobileAnchoredBullet).toBeInViewport()
    await expect(
      page.locator('[data-section-id="section-1"] .section-collapse-toggle'),
    ).toHaveAttribute('aria-expanded', 'false')
    const layout = await page.evaluate(() => {
      const app = document.querySelector<HTMLElement>('.app-page')!
      const stage = document.querySelector<HTMLElement>('.resume-stage-scroll')!
      const paper = document.querySelector<HTMLElement>('.resume-paper')!
      return {
        documentOverflow:
          document.documentElement.scrollWidth - document.documentElement.clientWidth,
        appOverflow: app.scrollWidth - app.clientWidth,
        stageOverflow: stage.scrollWidth - stage.clientWidth,
        paperOverflow: paper.scrollWidth - paper.clientWidth,
      }
    })
    expect(layout).toEqual({
      documentOverflow: 0,
      appOverflow: 0,
      stageOverflow: 0,
      paperOverflow: 0,
    })
  })

  test('opens narrow workspace in the editable document and keeps the outer page fixed', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await mockWorkspace(page)
    await page.goto('/workspace/42?requirement=3')
    const editorTab = page.getByRole('tab', { name: '编辑简历' })
    await expect(editorTab).toHaveAttribute('aria-selected', 'true')
    await expect(editorTab).toHaveAttribute('aria-controls', 'workspace-panel-editor')
    await expect(page.locator('#workspace-panel-editor')).toHaveAttribute(
      'aria-labelledby',
      'workspace-tab-editor',
    )
    await expect(page.locator('.resume-stage')).toBeVisible()
    await expect(page.locator('.workspace-inspector')).toBeHidden()
    await expect(page.locator('.workspace-requirements')).toBeHidden()
    const requirementsTab = page.getByRole('tab', { name: '岗位要求' })
    await requirementsTab.click()
    await expect(requirementsTab).toHaveAttribute('aria-controls', 'workspace-panel-requirements')
    await expect(page.locator('#workspace-panel-requirements')).toHaveAttribute(
      'aria-labelledby',
      'workspace-tab-requirements',
    )
    await expect(page.locator('.workspace-requirements')).toBeVisible()
    await expect
      .poll(() =>
        page.evaluate(() => {
          const list = document.querySelector('.requirement-list')?.getBoundingClientRect()
          const selected = document
            .querySelector('.requirement-item.is-selected')
            ?.getBoundingClientRect()
          return Boolean(
            list && selected && selected.left >= list.left - 1 && selected.right <= list.right + 1,
          )
        }),
      )
      .toBe(true)
    await page.getByRole('tab', { name: '编辑简历' }).click()
    const appMetrics = await page
      .locator('.app-page')
      .evaluate((element) => ({
        scrollHeight: element.scrollHeight,
        clientHeight: element.clientHeight,
        scrollTop: element.scrollTop,
        scrollWidth: element.scrollWidth,
        clientWidth: element.clientWidth,
      }))
    expect(appMetrics.scrollHeight).toBeLessThanOrEqual(appMetrics.clientHeight + 1)
    expect(appMetrics.scrollWidth).toBeLessThanOrEqual(appMetrics.clientWidth + 1)
    expect(appMetrics.scrollTop).toBe(0)
    const suggestionsTab = page.getByRole('tab', { name: /分析详情/ })
    await suggestionsTab.click()
    await expect(suggestionsTab).toHaveAttribute('aria-controls', 'workspace-panel-suggestions')
    await expect(page.locator('#workspace-panel-suggestions')).toHaveAttribute(
      'aria-labelledby',
      'workspace-tab-suggestions',
    )
    await expect(page.locator('.workspace-inspector')).toBeVisible()
    await expect(
      page.locator('.workspace-inspector').getByText('岗位要求', { exact: true }),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: '分析详情' })).toHaveCount(0)

    await page.getByRole('tab', { name: '编辑简历' }).click()
    await page.locator('.workspace-mobile-more summary').click()
    await expect(page.locator('.workspace-more-menu')).toContainText('撤销')
    await expect(page.locator('.workspace-more-menu')).toContainText('重做')
    await expect(page.locator('.workspace-more-menu')).toContainText('恢复优化前版本')
  })
})
