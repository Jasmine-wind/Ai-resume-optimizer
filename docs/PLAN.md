# CV-Role V2 重构计划

本计划只把 [PRD.md](PRD.md) 已冻结的决策转成实施顺序，不扩展产品范围。**Phase 1–9 均已通过独立 Final Gate；没有批准或创建 Phase 10。**

## 目标

把当前多入口、手动编排的 AI 功能集合，逐步迁移为：

```text
我的简历 + 目标 JD
→ 一键岗位分析
→ 区分当前材料中的足够证据、部分证据与未体现要求
→ 用户编辑 + 受约束 AI 修改
→ 岗位优化版本
→ Preview / PDF
```

## 实施原则

- 优先减少用户步骤，不优先增加内部能力。
- 先建立可迁移的数据模型和兼容层，再替换生产链路。
- 每阶段只有一个权威流程；临时兼容入口不得演变成第二套产品文档。
- 不以大规模代码改名代替业务迁移，不进行无关技术栈升级。
- 旧接口、表或页面只有在引用、数据迁移、回滚和验收都明确后才删除。
- P1 / P2 不进入 P0 主链路，不提前暴露高级概念。

## 阶段顺序

### Phase 0 — 仓库基线（已完成本次整理）

- 收敛产品、架构、计划、上下文与运维文档。
- 删除重复阶段日志、过期解析材料、废弃数据库脚本和无引用脚手架。
- 校验现有测试、构建、文档链接和部署配置。

门禁：当前实现与 V2 目标差距可被清楚区分，仓库不存在两套并行产品说明。

### Phase 1 — 信息架构与主链路简化（已完成）

- 首页聚焦“选择简历 + 粘贴 JD + 开始分析”。
- 自动触发解析，隐藏 Parse、Embedding、Prompt、Provider 等内部步骤。
- 一级导航收敛到首页、我的简历，可选优化记录。
- 岗位库和技术分类式 AI 历史退出主流程。

门禁：首次使用在上传简历、粘贴 JD 后即可获得分析结果；不要求高级配置。

完成状态：上传自动提交简历准备任务；首页提供“选择简历 + 粘贴 JD + 开始分析”；岗位分析由单个后台编排完成，失败重试复用已保存输入；一级导航只保留首页和我的简历；岗位库、目标岗位管理、旧匹配编排和技术分类式 AI 历史页面已退出前端路由。

### Phase 2 — 核心领域模型（已完成）

- 建立 `ResumeVersion`、`JobTarget`、`OptimizationTask` 及其用户归属。
- 保留原始简历、原始 JD、版本来源和任务输入 / 配置快照。
- 通过加法式 Flyway 迁移回填旧简历、JD 和匹配记录；保留旧表与兼容接口。
- Phase 1 首页、失败重试和结果页已改用正式优化任务作为业务身份。

门禁：同一原始简历可以安全派生岗位版本，历史任务可解释且不污染原始数据。

完成状态：正式表使用复合外键保持用户归属；每次新分析建立独立源快照版本和岗位派生版本；任务保存 Resume / JD、Prompt、Rules、Provider、Model、Template 快照；旧匹配数据已可验证回填，迁移不修改或删除 V1 数据，旧应用可直接回滚；前端不再以 `resumeId + jobDescriptionId` 组织主流程。

### Phase 3 — Evidence Matching 与 Gap Analysis（已完成，Gate 已通过）

- 建立岗位要求与当前冻结 ResumeVersion 证据的映射。
- 区分“当前材料足够支持”“当前材料只有部分证据”“当前材料未找到支持证据”。
- 无证据内容不得自动写入简历；后续 AI Suggest 如参考能力缺口，只能作为明确标注的候选并等待用户显式确认。

门禁：正式 Evidence 不替候选补全事实；能力缺口不会被 AI 自动写入简历。

完成状态：正式分析结果以每个 OptimizationTask 一条 `evidence_analyses` 及其 `evidence_requirements` / `requirement_evidences` 行为 Source of Truth，V20 新建正式表，V20.1 原位移除 EXPRESSION_GAP / expression_status 错误语义并将旧正式派生结果安全失效供重试；V1 历史数据不变。Requirement 必须回溯到冻结 JD；MATCHED / PARTIAL_EVIDENCE 必须有逐字命中冻结 ResumeVersion 且与要求相关的 Evidence，NO_EVIDENCE 不得保存 Evidence。PARTIAL_EVIDENCE 不代表用户现实中具有未被材料证明的经历，也不改变 Evidence 语义；Rewrite 只能提出 review advisory 候选，不能自动写入或伪造 Evidence。失败重试保留冻结输入，正式结果与任务成功状态原子提交。匹配策略位于 `EvidenceMatchingStrategy` interface 之后，不依赖 Embedding / RAG；结果页使用“已有优势 / 建议完善 / 当前材料未体现”并保留历史 V1 兼容读取；Workspace、AI Rewrite、Diff、Typst 未在 Phase 3 中实现。

Gate 结论：当前正式三态只陈述冻结材料可以支持的结论；引用真实性、用户归属、无证据降级、旧写入口、重试与结果原子性均已验证。真正的“有经历但没有写出来”明确留给未来用户确认或独立事实来源，不是 Phase 3 的隐含能力。

### Phase 4 — Optimization Workspace（已完成，Gate 已通过）

- 两栏主工作区：建议与编辑器；Preview 使用切换、Drawer 或独立模式。
- 支持 Section / Bullet 编辑、拖拽、Undo / Redo、自动保存和恢复本次优化前版本。

门禁：离开页面后编辑不丢失；失败不要求用户重做前置步骤。

完成状态：Workspace 以 `optimizationTaskId` 为唯一入口，服务端由任务解析 SOURCE / TARGET / JobTarget 完整版本链，前端不能指定可写 ResumeVersion；只有当前任务的 TARGET 岗位版本可编辑，SOURCE、resume_input_snapshot 与正式证据分析全程只读。结构化简历文档（RESUME_DOCUMENT_V1）是唯一编辑与持久化内容，落在既有 `resume_versions.structured_content`，未新增第二套内容字段；V21 只补充 `content_revision`（NOT NULL DEFAULT 0）用于乐观并发。保存必须携带 expectedRevision，仅版本一致时条件更新并递增；冲突返回服务端当前版本并保留本地草稿，用户显式覆盖时基于重新获取的最新 revision 再次条件保存。恢复优化前版本基于任务冻结的 resume_input_snapshot 确定性重生成并作为新 revision 写入。两栏页面左栏只读展示分析时三态结论与 SOURCE Evidence，并明确 TARGET 编辑后不实时重算；前端自动保存状态机覆盖 dirty / saving / saved / failed / conflict，离开守卫防止静默丢失；Undo / Redo 仅属当前会话。Preview / PDF、AI Rewrite、Diff、Apply / Reject 未在本 Phase 实现。

### Phase 5 — 单 Bullet AI Suggest、Diff 与本次策略（已完成，Gate 已通过）

- 上下文 AI 修改，提供 Diff、原因、拒绝、重新生成和采纳。
- 只组合平台真实性约束、系统默认策略与本次 request-scoped 要求。
- 用户 Profile / Rules 属于后续 P1，不在 Phase 5 范围；默认流程不暴露高级设置。

门禁：所有 AI 修改可审查、可撤销、可追溯；技术安全问题 fail closed，内容事实变化以用户可见的 review advisory 提示，不阻止 READY / Apply。

完成状态：单 Bullet 岗位定向改写以 `POST /api/workspace/{optimizationTaskId}/bullet-suggestion` 为唯一入口，完全只读、不落库：请求绑定 requestId、baseRevision、bulletId 精确身份与原文 SHA-256；服务端复用 Phase 4 任务版本链解析，无正式证据分析的旧版兼容任务 fail closed。平台真实性策略进 SYSTEM 消息，简历 / 岗位 / 用户要求等不可信数据进 USER 数据区；Prompt v3 使用单遍模板替换。AI 输出只接受字段完整、无重复 / 未知字段、无前后包装或 trailing token 的单一 JSON；`reason` 必须是非空字符串。`RewriteFactValidator` 识别新增技术、实体、数字、责任、成果、范围 / 时间等内容变化，返回 `reviewCode` / `reviewMessage`，不再把事实变化转为 REJECTED；空值、超长、内部身份泄露、控制字符和不支持脚本仍由技术安全门拒绝。NO_EVIDENCE 要求进入上下文并明确标记为当前材料未体现 / 候选方向，不改变 Evidence。Diff 由前端代码确定性生成。建议只存在于当前会话：Suggest / Reject / Regenerate 不修改草稿与服务端；Apply 必须显式点击并重新验证候选有效，只替换对应 Bullet，形成单个 Undo 节点后进入既有 dirty → Auto Save → CAS。用户 Profile / Rules 高级分层仍未实现。

Gate 结论：Prompt v3、严格 JSON Schema、技术安全 fail-closed、事实 review advisory、NO_EVIDENCE 上下文、READY + advisory 的 Apply / Undo 与低价值质量 Gate 均有回归覆盖。Phase 5 的“可追溯”仅指当前会话内可查看原文、候选、Diff、原因并可 Undo，不包含持久化 AI History 或 Change Event。

### Phase 6 — Typst Preview 与导出（已完成，Final Gate 已通过）

- Structured Resume JSON 作为唯一业务数据源。
- 建立 Classic / Modern / Minimal 模板、Preview、PDF 和导出前检查。
- 后续支持 Markdown / JSON 迁移导出，但不建设模板商城。

门禁：Preview 与 PDF 基本一致，编译或排版失败可恢复，导出物有归属和生命周期。

完成状态：固定链路为 Editor CAS Save → TARGET `resume_versions.structured_content` 中的 `RESUME_DOCUMENT_V1` → Preview → PDF Export。Phase 4 仍允许 revision 0 的冻结 snapshot 投影初始化编辑器，但 Phase 6 严格读取 seam 会拒绝 revision 0；前端在首次 Preview 前显式执行一次原样 CAS Save，因此 SOURCE、任务快照与 Evidence 不进入渲染。Preview 返回短期服务端签名 receipt，完整绑定 user / task / TARGET / contentRevision / templateId+version / rendererVersion / PDF checksum；Export 必须提交并验证该 receipt，重新编译字节不一致、无 Preview、stale revision 或任一绑定变化均拒绝。

三套内置只读模板（classic / modern / minimal 各 v1）共享同一 Typst Renderer、编译器和字体环境。最终 PDF 由 PDFBox 解析实际页数；联系方式检查只认可电话、邮箱、社交账号或 URL 等通信方式；overflow 的可执行边界冻结为“文字 glyph 边界框超出页面 CropBox 1pt 以上”，页面超过 2 页、缺少联系方式和越界均作为轻量告警展示，不自动改写内容。V22 建立带 task→TARGET 复合约束的 `export_artifacts`，保存 preflight 与 READY / DELETE_PENDING 状态。删除采用 DELETE_PENDING → 私有对象删除 → 元数据删除，失败保留可重试记录；Resume 与 JobDescription 两个真实父删除入口均先完成导出物清理再允许数据库级联。未引入 Template entity、HTML renderer、RenderJob、MQ 或后台清理系统。

Final Gate 结论：真实 Typst、Fresh PostgreSQL/Flyway、HTTP、跨用户、stale receipt、存储失败重试和父级联 E2E 均通过；Phase 6 冻结 Contract 的 Blocker / Major 已关闭，**Final Gate PASS**。

### Phase 7 — BYOK 与 AI Gateway

- OpenAI-compatible Provider、用户 Credential、模型配置和统一 Gateway。
- 完成密钥加密、掩码、测试 / 替换 / 删除、SSRF 防护和 Usage 记录。

门禁：用户配置不能覆盖平台真实性、安全指令或核心 Schema。

完成状态：全仓 Chat 调用收敛为唯一 seam `业务 → AiGateway（ContextAwareAiGatewayService）→ OpenAI-compatible Adapter → PinnedHttpTransport`；业务调用只提交受控 policy ID、服务端可信策略和不可信 USER 数据，Gateway 统一追加平台 guardrail 并构造 SYSTEM / USER。每用户至多一个 `OPENAI_COMPATIBLE` Credential，支持 Save/Replace/Test/Enable/Disable/Delete，Create/Replace 默认 DISABLED，配置变更 `credential_revision++`。API Key 只以 AES-256-GCM versioned envelope 持久化，AAD 绑定 user/credential；key ring 启用时启动即校验，旧 key 密文可在不改变 Credential revision 的前提下重加密。

SSRF transport 仅允许 HTTPS / DNS hostname / 443；每次请求有界解析并校验全部 A/AAAA，连接只使用已验证地址，同时 TLS SNI / 证书仍绑定原 hostname。Redirect 永不跟随、隐式 Proxy 不生效，DNS / connect / TLS 各有硬限制，绝对总 deadline 可取消 trickle response，成功与错误响应均按解码后 1 MiB 限制。新 OptimizationTask 在异步分析前冻结无 Secret 的 Selection Snapshot；同任务 Resume / JD / Evidence / Rewrite 复用该快照，Credential 变化或删除后的旧 BYOK Retry fail closed，历史 Workspace / Preview / Export 仍可读。Usage 按 Provider attempt 记录最小字段，失败不影响已经完成的 AI 结果。V23 采用加法式迁移并以复合外键约束 Credential / Task / Usage 用户归属；Settings 不进入首次使用主链。

Final Gate 结论：唯一 Gateway、Credential 加密与生命周期、真实 socket DNS pinning / TLS / redirect / proxy / decoded response limit、task snapshot、System Default / BYOK 选择、attempt Usage、Fresh V1→V23 与 V22→V23、Phase 3 / 5 / 6 回归均已独立验证；发现的 Blocker / Major 已关闭，**Final Gate PASS**。

### Phase 8 — 视觉与状态体验（已完成，独立 Final Gate 已通过）

- 统一 Landing、Workspace、Loading、Error、Empty 和保存状态。
- 视觉保持克制、专业、内容驱动；移除指标墙、重复 Card 和内部术语。

门禁：一级导航不超过 3 个；第一屏只展示用户需要采取的决策。

完成状态：Landing 收敛为单主 CTA + 登录次 CTA + 3 个价值点，移除指标墙 / 功能卡墙 / dashboard 预览；首页形成“选择或上传简历 + 粘贴 JD + 开始分析”连续操作面，分析失败明确提示可重试与已保留输入；我的简历区分真正空数据与加载失败；岗位分析页按建议完善 → 已有优势 → 当前材料未体现排序，主 CTA 修改简历 / 次 CTA 分析新岗位；Workspace 保持 Phase 4 状态机并统一状态文案（修改未保存 / 正在保存 / 已保存 / 草稿仍在，可重试 / 本地草稿未保存，请解决冲突），未保存时 Suggest 锁定并提示“请先完成保存，再生成建议”；Preview / Export 保持 Phase 6 receipt / preflight 语义，模板前台名称收敛为经典 / 现代 / 简洁，不再向用户暴露 revision / renderer 等内部术语；AI 设置仅从账号菜单进入，先声明普通分析无需配置；一级导航保持首页 / 我的简历，窄屏 sidebar 变为可键盘操作的 Drawer，Workspace 单列降级；全局样式删除约 1800 行失效样式并收敛 token（弱边框、少阴影、克制圆角、单一主色）。Element Plus 改为按需引入 + 路由懒加载，960 kB 大 chunk 警告消除，入口 chunk 降至约 160 kB；同时修复结构化编辑器对响应式 Proxy 使用 structuredClone 导致手工编辑崩溃的缺陷（改为 JSON 克隆并有回归测试）。独立 Gate 另行关闭了过期 Preview / Export 响应覆盖本地草稿、导出记录加载伪装为空数据和初次分析启动失败不可见的问题；Phase 1–7 业务语义、状态机与安全边界未被改变，后端无业务改动。

Final Gate 结论：真实主链、Desktop / Narrow 浏览器、Workspace 失败与冲突状态、Preview / Export、前后端全量测试、前端 clean build 与 bundle 检查均通过；Contract 内发现的 Blocker / Major 已关闭，**Final Gate PASS**。

### Phase 9 — 长期洞察、观测与 E2E（已完成，Final Gate PASS）

- 新增只读 Multi-JD Insight：仅从当前用户近 180 天 `SUCCESS` 正式 Task/Evidence 派生；cohort 固定为 `resumeId + frozen resume snapshot hash`，同一冻结 JD 仅取最新成功 Task，最多 20 个不同 JD，至少 8 个才显示。
- Requirement 只做保守的字面技术锚点或精确规范化分组；展示 `MATCHED / PARTIAL_EVIDENCE / NO_EVIDENCE` 分布和 Task / Requirement / Evidence 追溯，不生成 Capability 事实、不重写历史结果、不建聚合表或 cache。
- 新增内部 committed-fact Observability 查询，不建立通用事件平台；Usage 仍是每次真实 Provider dispatch 的 ledger，改为 `REQUIRES_NEW` best-effort 写入，正式 JD parse / Evidence 调用绑定 OptimizationTask，原始 attempt 元数据 90 天清理，模型输出不写日志。
- 建立真实 PostgreSQL/Flyway + MinIO lifecycle integration、确定性 fake Provider、Playwright Chromium 三条恢复型浏览器流和 CI job；覆盖 happy path、同 Task 分析失败重试、CAS 本地草稿冲突、stale Preview / Suggest、Preview / Export。
- 新增仅在 `demo` profile + `APP_DEMO_ENABLED=true` 下运行的普通 User 合成 seed、loopback-only 专用 Compose 和显式确认 reset 脚本；Demo 禁用 BYOK 和外部 AI，不创建 DemoAccount 或特殊权限。

门禁：单 JD 主流程始终独立可用；洞察只在数据足够时出现；所有 Phase 9 Gate 验证完成前不得宣布 PASS。

Final Gate 结论：只读 cohort、正式 Evidence 追溯、保守分组、实际 Provider-dispatch Usage ledger、`REQUIRES_NEW` 隔离与 retention、真实 PostgreSQL/Flyway/MinIO/Typst/fake Provider Chromium E2E、Demo bootstrap/reset 和 Phase 1–8 回归均已独立验证；Contract 内发现的 Blocker / Major 已最小修复并复测，**Final Gate PASS**。

### Product Polish — Slice A 可信交付链（已完成）

Phase 1–9 完成后进入 Product Polish，不新建 Phase、不扩展产品范围。Slice A 只冻结高返工风险边界，修复“解析后联系人丢失 / 错位、章节与条目边界错误、换行被拆成 Bullet、未识别内容进入正式文档，且仍能导出看似正式但不可投递的 PDF”的问题。

- canonical 交付文档保持 `RESUME_DOCUMENT_V1`：联系方式携带显式类型，条目按章节语义携带公司 / 职位 / 学校 / 学历 / 专业 / 起止时间原文，技能为一等技能组；`heading/meta` 仅作历史 generic V1 只读兼容。canonical JSON 只物化在 `resume_versions.structured_content`，解析结果只保存当前 SOURCE 指针和审查 sidecar；不静默改写历史 `resume_versions`。
- 候选解析不是事实：新增确定性验证与 `PENDING / READY / NEEDS_REVIEW / FAILED` 质量状态（裁决 SoT 在 `resume_parse_results`，canonical 内容 SoT 仍在 `resume_versions.structured_content`，V24 加法式迁移）。无法可靠判定归属的内容进入未决候选由用户确认，AI 不为修解析补造事实，无法裁决时 fail closed；非 `READY` 或没有 canonical SOURCE 禁止创建新分析任务，历史任务不受影响。
- 导出分两层质量门：Document Gate（质量状态 / 系统兜底章节 / 重复章节 / 可用联系方式）与 PDF Gate（编译 / 越界 / 孤立末页 / 不可读字号阻断，页数告警）；两页合法、孤立末页不合法。预览在待确认时仍可用作审查，正式导出阻断。
- 渲染消费 V1 语义模型并按章节类型分支，模板升为 v2、渲染器升为 `typst-resume-renderer/2`；v1 模板保留供历史导出物解释。本 Slice 不做模板视觉重设计。
- 修复解析层具体缺陷：头部混合联系行不再丢弃姓名 / 电话、邮箱按 `find()` 抽取、组织名扫描整行、技能标题别名、指针回退不再整章节兜底、抽取去重保留跨章节合法重复。
- 明确不做：Analysis IA 重构、Workspace 视觉重构、多模板视觉设计、ATS、新 Resume Builder、AI Chat、Career Profile / Fact Vault、Markdown / JSON 导出、编辑内容回流分析链。

门禁：真实风格中文简历夹具联系方式不丢失 / 不错配、经历 / 项目 / 技能 / 教育边界正确、无系统兜底章节且正常简历 `READY`；歧义夹具进入 `NEEDS_REVIEW` 且不补造事实；未确认 / 越界 / 孤立末页阻断正式导出而合法单页与两页可通过；SOURCE / TARGET SoT、Evidence / Rewrite / CAS / Preview receipt / BYOK 与历史数据不回退，后端全量测试、前端构建 / 类型 / 单测与三条浏览器恢复型 E2E 通过。

### Product Polish — Slice B UX（已完成，Final Gate PASS）

- 前端已完成行动优先 Analysis、编辑器优先 Workspace contextual inspector、语义化 Resume 字段、纵向 Suggest / Diff、Workspace 内“编辑 / 预览”模式和冗余字段收敛。
- 新增 no-op Suggest 防护与前端回归覆盖；未改变 Phase 1–9 / Slice A API、数据源、状态机、真实性、CAS、Preview receipt 或 Export gate。
- 独立 Final Gate 已验证混合 Analysis、Resume-first Workspace、语义化编辑、Suggest / Diff / Preview、1440×900 与 390×844 浏览器体验，以及 Phase 1–9 / Slice A 回归；Contract 内问题已最小修复并复测，**Final Gate PASS**。

### Product Polish — Slice C PDF / Preview / Visual Polish（已完成，Final Gate PASS）

- 保持 `RESUME_DOCUMENT_V1` 与用户显式 section order；默认 canonical 投影采用 Summary → Experience → Projects → Education → Skills → Other，模板不静默重排已编辑内容。
- 三套 current template 升为 v3；Summary、Education、Achievement、Certificate、Other 使用无 marker 文本，Experience / Project 使用真实 Bullet；长字段通过 gutter 和安全分页处理，中英正文左对齐。
- 三模板共用 A4 排版边界与字号目标，生产镜像固定静态 Noto CJK Regular/Bold 字体；PDF 写入稳定 title/metadata，保留浏览器原生 viewer，不引入 PDF.js。
- PDFBox 新增末页 glyph 占用比例；低于 20% 与既有末页行数共同阻断稀疏尾页。补充标准中文、合法两页、中英混排、长字段和两个真实风格 JD fixtures，并扩展 Typst/PDF/Playwright 回归。
- 未新增业务 API、DB model、Schema、Phase 10 或其它产品能力。
- 独立 Final Gate 已复核真实三模板 PDF、固定字体、长字段与通用章节自然分页、Preview / Export、1440×900 与 390×844 浏览器体验、Fresh PostgreSQL/Flyway、Docker/Compose 与完整回归；Contract 内问题已最小修复并补测，**Final Gate PASS**。

Implementation status：**Product Polish Slice C 已完成，Final Gate PASS**。

## 优先级边界

- **P0**：主链路、版本、证据 / Gap、Workspace、编辑、Diff、自动保存、Typst / PDF、真实性与隔离、安全 Gateway。
- **P1**：BYOK UI、Profile / Rules、多模板、Markdown / JSON 导出、Usage、数据导出 / 删除、多 JD 洞察、Demo Account。
- **P2**：自定义 Typst、多 Provider Profile、复杂 Track Analytics、插件式 Analyzer、多模型路由。

明确不做：招聘爬虫、自动投递、Cover Letter 大模块、Chatbot 主界面、岗位库、复杂 ATS 分数、模板商城、插件市场、社交功能。

## 每阶段通用验收

1. 现有数据可迁移或兼容读取，并有回滚路径。
2. 用户资源隔离、日志脱敏和事实边界不回退。
3. 后端测试、前端构建、相关回归与关键用户流通过。
4. 当前事实更新到 `CONTEXT.md`；产品决策只更新 `PRD.md`。
5. 删除旧链路前完成全仓引用检查，不保留长期双流程。
