# 架构基线

本文记录**当前仓库真实实现**及其向 V2 演进时必须保持的边界。V2 产品决策以 [PRD.md](PRD.md) 为准；本文不另行设计产品。

## 1. 当前系统

```text
Vue 3 SPA
   │ /api + JWT
   ▼
Spring Boot 单体应用
   ├─ PostgreSQL + Flyway + pgvector
   ├─ Redis（非关键、可重建缓存）
   ├─ Local / MinIO（简历文件）
   ├─ OpenAI-compatible Chat API
   └─ OpenAI-compatible Embedding API
```

生产环境由 `docker-compose.prod.yml` 编排 Nginx、backend、PostgreSQL、Redis、MinIO 和 certbot。Nginx 托管前端并反向代理 `/api`；后端不直接暴露公网端口。

## 2. 仓库与模块职责

```text
backend/  后端应用、数据库迁移和测试
web/      前端应用
docs/     长期产品、架构、计划、上下文与运维文档
deploy/   Nginx 配置
scripts/  生产运维脚本
```

后端根包：`com.winter.airesumeoptimizer`。

| 区域 | 职责 |
|---|---|
| `common/` | 统一响应、异常、日志等跨模块能力 |
| `config/` | Spring、安全、缓存、异步执行器配置 |
| `security/` | JWT、认证上下文、401 / 403 处理 |
| `infra/` | AI、Embedding、Redis、文件存储、Typst 渲染等外部适配 |
| `module/` | 业务模块与用例编排 |

当前业务模块：

- `auth` / `user`：账号、登录和当前用户；`PATCH /api/users/me` 只允许 JWT 当前用户更新 nickname，返回 `UserProfileVO`，username / email 保持只读。
- `resume`：简历文件、解析、质量检查与展示模型。
- `job`：预置岗位、用户目标 JD、岗位解析和旧匹配。
- `analysis`：诊断、优化建议、局部改写和聚合报告；旧 AI 匹配仍在其中，公开写入口已停用，仅服务历史兼容读取，不再是主链路正式结果。
- `optimization`：`ResumeVersion`、`JobTarget`、`OptimizationTask`，负责版本派生、输入与配置快照、任务归属和正式结果入口。
- `workspace`：Phase 4 优化工作区与 Phase 5 单 Bullet AI Suggest；以 `optimizationTaskId` 为唯一入口解析任务版本链，提供 TARGET 岗位版本的结构化简历文档读取、基于 `content_revision` 乐观并发的条件保存与恢复优化前版本，以及只读生成、技术安全校验、事实审查 advisory 和会话内显式采纳。
- `export`：Phase 6 PDF Preview / Export；只读取至少完成一次 CAS Save 的 TARGET `RESUME_DOCUMENT_V1`（Slice A 为其增加语义字段，历史 generic 形态只读升级），生成带实际 preflight 和签名 receipt 的 PDF Preview；Export 验证 receipt 完整绑定并通过文档 / PDF 两层质量门后创建私有 `ExportArtifact`，并维护 READY / DELETE_PENDING 可重试生命周期。
- `evidence`：Phase 3 正式 Evidence Matching 与 Gap Analysis；岗位要求、简历证据与匹配结论的正式 Source of Truth。
- `embedding`：文本分块、向量生成、相似度与 RAG 上下文；当前不进入正式证据匹配主链路。
- `history`：旧历史聚合与 AI 结果回看。
- `task`：单进程异步执行记录、归属校验和状态查询；不再承担正式优化业务模型。
- `insight`：Phase 9 的只读 Multi-JD 聚合 seam；只读 Task / SOURCE / Evidence，不能创建 Capability 或修改任何正式事实。
- `observability`：只查询 committed retained rows 的内部汇总；没有 product event、用户 dashboard 或事件存储。
- `ai/usage`：Provider attempt ledger、独立事务写入和 90 天 retention；不是产品漏斗 Source of Truth。
- `demo`：只在明确 `demo` profile + property 下创建合成普通 User 数据，不参与生产域模型或授权。

前端按 `api/`、`components/`、`layout/`、`router/`、`stores/`、`types/`、`utils/`、`views/` 分层。页面包含 Landing、首页、我的简历、按正式优化任务访问的岗位分析结果、按正式优化任务访问的优化工作区、达到样本门槛后从首页进入的岗位方向洞察、账户设置（个人资料 / AI 设置）、登录和注册；一级导航仍只有首页和我的简历，设置与洞察都不进入首次使用步骤。全局壳只承担 navigation / account / 窄屏菜单（窄屏 sidebar 为 Drawer），账号菜单提供账户设置和 AI 设置快捷入口；Settings shell 在桌面使用窄侧栏，在窄屏使用横向 tabs。页面标题与任务操作由各页面自身承担。Preview / Export 以 Workspace 内的“编辑 / 预览”模式集成，Preview 使用完整文档阅读区域，仅保存成功状态可用；Workspace 当前采用岗位要求导航 / 简历编辑器 / contextual inspector 的三栏任务壳，检查器只展示当前岗位要求上下文，完整分析仍留在 Analysis 页面，窄屏转为要求横向条 + 编辑 / 建议切换。Element Plus 由 unplugin-vue-components 按需解析，路由懒加载；视觉变量集中在 `styles/tokens.scss`（近白背景、弱边框、少阴影、克制圆角、单一主色），状态色只用于真实反馈。

## 3. 当前主链路

```text
注册 / 登录
→ 选择已有简历，或上传后由 ResumeIntakeService 自动提交准备任务
→ 在首页粘贴目标岗位 JD
→ OptimizationTaskService 原子保存兼容 JD、正式 JobTarget、源 ResumeVersion、岗位派生 ResumeVersion 和 OptimizationTask
→ JobAnalysisService 以 OptimizationTask 为业务主键，在后台确保简历可用并冻结输入快照，再解析 JD，随后由 EvidenceMatchService 生成正式证据分析
→ 失败时按 OptimizationTask 重试，复用已保存输入且不创建新版本；成功任务不可重试改写；重试会整体替换旧的正式分析行
→ 岗位分析结果页只通过 OptimizationTask 读取正式证据分析；历史任务无正式分析时兼容读取旧匹配结果
→ 任务成功后可进入优化工作区：服务端把冻结解析快照转换为结构化简历文档，用户对 TARGET 岗位版本做 Section / Bullet 编辑、排序与恢复优化前版本，自动保存以 content_revision 条件更新落库
→ 用户可对明确选中的单个 Bullet 请求只读 AI Suggest；候选经严格解析与技术安全校验后展示代码 Diff，事实变化以 review advisory 提醒，显式 Apply 才进入既有 Undo / Auto Save / CAS
→ 保存成功后可在 Preview / Export 中选择内置模板，同步渲染服务端已保存内容得到 PDF；导出成功后生成带归属与生命周期记录的导出物
```

`ResumeIntakeService`、`JobAnalysisService` 与 `OptimizationTaskService` 是默认用户流的深模块 seam：前者负责上传与准备，第二个负责后台分析编排，第三个负责正式业务身份、版本关系和快照。调用方不需要编排 Parse、Embedding、Prompt 或供应商步骤。`WorkspaceContentService` 是 Phase 4 的编辑 seam：只接受 optimizationTaskId，内部解析并校验 Task → SOURCE / TARGET / JobTarget / Resume / User 完整版本链，把候选解析经确定性验证与未决候选裁决后唯一物化为 `resume_versions.structured_content` 中的 RESUME_DOCUMENT_V1 SOURCE；解析表只保存当前 SOURCE 指针和审查 sidecar。任务引用该冻结 SOURCE 并把 canonical 文档写入 task 的 SOURCE/TARGET 快照，以单条条件 UPDATE 实现 expectedRevision 乐观并发；Workspace 不回写当前解析结果、任务输入快照或证据分析。Phase 3 已建立正式 Evidence / Gap 模型：正式分析结果是每个任务一条 `evidence_analyses` 及其 `evidence_requirements` / `requirement_evidences` 行，每条岗位要求只按当前冻结材料的支持强度判定为足够支持（MATCHED）、存在相关但不完整证据（PARTIAL_EVIDENCE）或未找到支持证据（NO_EVIDENCE）。具体匹配实现位于 `EvidenceMatchingStrategy` interface 之后（当前为单次 AI 结构化输出 + Requirement / quote / ResumeVersion 代码校核），后续可在不改动编排的情况下替换。该模型不判断用户现实世界中的完整能力，也不保留 EXPRESSION_GAP 兼容语义。

`ResumePdfRenderer` 是 Phase 6 的渲染 seam：把结构化简历文档确定性映射为转义后的 Typst 数据文件，在隔离临时目录中用内置版本化模板（Classic 当前 v4、Modern / Minimal 当前 v3，历史 v3/v2/v1 保留供导出物解释；按章节类型分支；渲染器版本仍为 `typst-resume-renderer/3`）同步编译为 PDF；固定创建时间戳与文档 metadata 使相同输入逐字节确定，PDFBox 随后解析真实页数、字号、末页 glyph 占用并检查文字是否超出页面 CropBox。渲染进程以 `--root` 限制文件读取，内置模板不引用外部包且用户内容无法触发 Typst 语法；生产镜像通过 `APP_RENDER_FONT_PATH` 指向只含审核过的静态 Noto CJK Regular/Bold 字体目录，并让 Typst 忽略宿主机字体，避免 Thin/variable fallback；当前没有 OS 级网络沙箱，该防御深度限制记录为残余风险。

`WorkspaceExportService` 只调用 `WorkspaceContentService.getPersistedContentForRender`，因此 revision 0 的 snapshot 投影不能渲染。Preview receipt 由服务端短期签名并绑定 user / task / TARGET / revision / template+version / renderer / PDF checksum；Export 重新校验并重编译比对。`ExportArtifactCleanupService` 用独立小事务持久化 DELETE_PENDING，再删除对象和元数据；对象或元数据删除失败时记录仍可重试。Resume 与 JobDescription 是当前仅有的真实父删除入口，均在级联前调用该 seam。

重要边界：

- 岗位库、目标岗位管理、技术分类式 AI 历史和旧匹配编排页面已退出前端路由。`job_descriptions`、`ai_job_match_results`、旧服务与旧接口仍供解析与兼容读取；正式主链路不再写入新的旧匹配行，也不用其 ID 作为前端路由或重试身份。
- AI 输出通过受控 DTO / Schema 解析后才能进入业务流程；单 Bullet Suggest 只在会话中展示，不把模型原文直接当可信业务数据。MATCHED / PARTIAL_EVIDENCE 都必须有逐字命中冻结 ResumeVersion 且与 Requirement 相关的 Evidence；失去全部有效 Evidence 时强制降级为 NO_EVIDENCE，NO_EVIDENCE 不保存 Evidence。
- PARTIAL_EVIDENCE 只表示当前材料的证据不完整，不改变 Evidence 语义。Rewrite 可以提出材料中没有明确写出的候选，但必须以 review advisory 提示用户确认，不得伪造 Evidence 或自动写入。
- 优化报告聚合已有结果，不为补全报告再次调用 AI。
- Redis 只缓存可重新生成内容，不承担唯一业务状态。
- 文件访问经过后端鉴权；MinIO bucket 不作为公开下载入口。
- 业务数据库结构只由 `db/migration/` 下的 Flyway 迁移维护。
- 异步任务是真实状态机，不伪造进度百分比。
- Phase 6 仅以已 CAS 持久化的 TARGET `RESUME_DOCUMENT_V1` 渲染（Slice A 之前的 generic V1 内容读取时只读升级），不使用 HTML、原始解析候选、任务输入快照或证据分析，不反解析 PDF。revision 0 仍用于 Phase 4 初始化，但必须先原样 CAS Save 才能 Preview。模板是内置只读资源且不保存业务数据；未引入后台渲染任务。
- Phase 9 Insight 在读取时按 `(userId, resumeId, SHA-256(resume_input_snapshot))` 建立兼容 cohort；近 180 天、去重后的最新 Task、最多 20 个不同 JD、至少 8 个样本。技术锚点只表示“岗位要求包含该字面词”，否则要求文本精确分组；输出只展示三态分布和原始追溯。
- Phase 9 Observability 从既有业务表和 `ai_usage_records` 查询已提交事实。Usage 每次真实 dispatch 都独立记录，ledger 写入失败不改变业务结果；它不得被用作逻辑漏斗分母。Prompt、输入、输出、URL、Key 和成本不进入 ledger 或日志。
- `demo` profile 使用确定性 in-process Provider，而生产 profile 仍只装配 pinned OpenAI-compatible Adapter；Demo 环境禁用 BYOK，采用普通 JWT/ownership/Storage/Typst 路径。

## 4. 数据与外部系统

- PostgreSQL 是账号、简历元数据、解析结果、岗位、分析结果、向量和任务的事实来源；`export_artifacts` 只记录 PDF 派生文件的归属与位置，不是内容来源。
- V20.1 将 Phase 3 正式状态原位收敛为 MATCHED / PARTIAL_EVIDENCE / NO_EVIDENCE，并把 Evidence 支持程度收敛为 SUFFICIENT / PARTIAL；旧语义生成的派生分析会失效并保留冻结输入供重试，V1 历史结果不受影响。
- V22 加法式建立 `export_artifacts`；模板源码随应用打包，不存在 Template 表。
- V23 加法式建立用户加密 Credential、OptimizationTask AI Selection Snapshot 与最小 attempt Usage ledger；System Default Secret 不进入数据库，Credential / Task / Usage 使用复合用户归属约束。Phase 9 没有新增业务表或 migration；Insight 与 Observability 均使用已有事实表。
- pgvector 当前用于简历 / JD 分块语义检索；向量不可用时部分 AI 链路可以降级。
- 简历原文件由 `FileStorageService` 抽象访问，本地开发默认 local，生产默认 MinIO。
- Chat 只通过业务侧 `AiGateway` 调用 OpenAI-compatible Adapter；生成式 Chat 为 BYOK-only，Embedding 保持独立的 platform-only 语义检索基础设施。二者共享 pinned HTTPS transport，密钥不得进入前端、日志或 Git。AI settings GET 只返回 `credentialStorageAvailable` 与用户 Credential 状态等 capability，不返回任何 secret；BYOK storage 未启用时仍可 Test，但不能 Save。
- `application.yaml` 提供公共默认值，`application-local/dev/test/prod.yaml` 负责环境差异；`application-demo.yaml` 与 `application-phase9-e2e.yaml` 明确限制为 fake Provider 的非生产环境。

## 5. 后续 V2 目标架构边界

Phase 2 已完成核心领域模型和主链路迁移。Phase 3 已把 Evidence Matching 与 Gap Analysis 收紧为当前材料可证明的三态并通过 Gate；Phase 4 已建立 Optimization Workspace 与结构化简历编辑并通过 Gate；Phase 5 已实现单 Bullet AI Suggest / Diff / Apply / Reject / Regenerate 并通过 Gate；Phase 6 已完成 Typst Preview / PDF Export / ExportArtifact，Final Gate 已通过；Phase 7 已完成 BYOK / AI Gateway 并通过独立 Final Gate；Phase 8 已完成视觉与状态体验统一（仅前端，独立 Final Gate 已通过）；**Phase 9 已通过独立 Final Gate，Phase 1–9 均正式完成，且未批准或创建 Phase 10。** V2 不推翻前后端分离和模块化单体基础：

```text
已完成：Resume / ResumeVersion / JobTarget / OptimizationTask
      Evidence Mapping / Gap Analysis（正式结果与追溯模型）
      Workspace / Editor（结构化简历编辑、自动保存与恢复优化前版本）
      单 Bullet AI Suggest / Diff / Apply / Reject / Regenerate（严格解析、技术安全校验与事实 review advisory，Gate 已通过）
      Typst Preview / ExportArtifact（签名 Preview receipt、preflight、可重试生命周期，Gate 已通过）
      AI Gateway / Provider Credential（唯一 Chat seam、BYOK 加密与 SSRF 防护，Final Gate 已通过）
      视觉与状态体验统一（Phase 8 独立 Final Gate 已通过）
      长期洞察 / 最小观测 / PostgreSQL-Flyway-MinIO-fake Provider E2E / 非生产 Demo（Phase 9 Final Gate PASS）
```

迁移时遵守：

1. **先收敛用户链路，再迁移内部模型**；不得先把 Provider、Prompt、Embedding、Task 暴露给普通用户。
2. **原始简历、原始 JD 与用户修改可追溯**；岗位版本不得静默污染原简历。
3. **事实审查与技术安全先于生成落库**；当前材料的 Evidence 强度与用户现实能力必须明确分开。Rewrite 可提出候选，未获用户显式 Apply 前不得写入；原文未出现的信息通过 review advisory 提醒确认。
4. **所有资源显式归属用户**；查询至少包含 `current_user + resource_id`。
5. **OptimizationTask 保存输入和配置快照**；后续配置变化不得改写历史解释。
6. **Structured Resume JSON 是编辑和导出的业务数据源**；Typst 只是输出基础设施。
7. **保持可回滚迁移**；在替代链路验收前，不删除仍服务当前生产流量的接口或数据。
8. **不拆微服务、不引入消息队列或 Kubernetes**，除非未来有独立决策基线替代 PRD。

## 6. 安全与可靠性

当前必须继续保持：JWT 鉴权、参数校验、资源归属检查、日志脱敏、上传限制、私有对象存储、环境变量注入。

Provider / BYOK 已实现并必须继续保持：服务端 AES-256-GCM 加密、掩码显示、替换与删除、复合用户归属、任务级 Selection Snapshot、真实地址 pinning 的 SSRF 防护、Redirect / Proxy / Timeout / 解码后 Response Size 限制、稳定错误映射和 attempt Usage。生成式 Chat 没有 System Default fallback；新任务必须使用 ACTIVE `USER_BYOK`，缺失时返回 `AI_CONFIGURATION_REQUIRED`。历史 `SYSTEM_DEFAULT` 只允许读取已保存内容，需要重新调用 AI 时 fail closed。Credential 保存只做 `BaseUrlPolicy.validateStructure`，Test 和实际 outbound 仍做完整 DNS 安全校验；这不是安全边界放宽。

Resume 与 JD 均视为不可信数据，不能覆盖平台指令、Schema 或真实性约束。

## 7. 验证边界

- 后端：`cd backend && ./mvnw test`
- 前端：`cd web && npm run build`
- 部署配置：`docker compose config`（本地）和 `docker compose -f docker-compose.prod.yml --env-file <env> config`（生产模板）

当前 CI 执行 PostgreSQL/Flyway + MinIO 后端测试、前端 unit/type/lint/build，以及由 deterministic fake Provider、PostgreSQL、MinIO、Typst 和 Chromium 组成的浏览器 E2E。V2 每个阶段必须在不破坏现有主链路的前提下增加针对新模型和新用户流的验证。

## 当前 BYOK-only 决策

生成式 Chat LLM 的正式运行路径不读取平台 Chat API Key、Base URL 或 Model 作为用户 fallback。新任务必须在创建任务前解析当前用户 ACTIVE Credential，并冻结不可变的 `USER_BYOK` selection；没有配置或仅保存未启用时直接返回 `AI_CONFIGURATION_REQUIRED`。`SYSTEM_DEFAULT` 数据库枚举仅为历史兼容，不能创建新任务，也不能 Retry / Regenerate / AI Suggest。Embedding-compatible 仍是独立的内部检索基础设施，不能替代 Chat generation。生产固定 `AI_CREDENTIALS_ENABLED=true`，active key id 与 key ring 缺失时启动失败；local/test 可关闭以支持无 AI 页面和 deterministic fake Provider。
