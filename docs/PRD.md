# AI Resume Optimizer V2：产品与架构重构基线（最新版）

> **用途**：V2 重构最高层决策基线，用于后续 PRD、ARCHITECTURE、PLAN、TASK 与 Coding Agent 执行。
>
> **目标**：把当前“AI 功能集合”重构为一个 **简单、真实可用、视觉成熟、可信可控、可长期使用，同时对高级用户保持高扩展性** 的岗位定向简历优化产品。
>
> **最高原则**：**用户只负责提供真实经历、目标 JD 和自己的偏好；系统负责理解其余复杂性。**

---

# 0. 最终产品定义

## 一句话定义

**用户上传或维护自己的真实简历，粘贴目标岗位 JD，系统分析“岗位要求 ↔ 当前已确认材料”之间的证据关系，识别哪些要求已有足够支持、哪些只有部分证据、哪些在当前材料中未体现，再帮助用户在事实边界内可控地修改并生成岗位定向 PDF。**

```text
我的简历 + 目标 JD
        ↓
     岗位分析
        ↓
岗位要求 ↔ 当前已确认材料
        ↓
   建议完善 / 当前材料未体现
        ↓
 AI 辅助 + 用户自行编辑
        ↓
      优化版本
        ↓
      Typst
        ↓
   Preview → PDF
```

---

# 1. V2 不是什么

V2 **不是**：

- Resume Matcher 的 Java 复刻
- AI Resume Builder
- ATS 分数生成器
- AI 功能展示平台
- Prompt Playground
- 招聘网站聚合器
- 自动投递系统
- AI Chatbot 简历助手

V2 的核心不是：

> “AI 帮你生成一份简历。”

而是：

> **“针对一个真实岗位，告诉你当前材料哪里已有足够证据、哪里建议完善、哪里尚未体现，再在真实事实边界内帮助你完成一份可投递版本。”**

---

# 2. 为什么仍然值得做

“Resume + JD → Tailor → PDF”已经是成熟赛道标准能力。

常见项目已经覆盖：

- Resume Matcher：Master Resume、JD Tailor、Builder、Template、PDF、多 Provider
- Smart Resume Matcher：真实经历约束、Side-by-side、Accept/Reject、实时 Preview、多 Provider
- ATS Beater：结构化 Profile、JD 定制、编辑、LaTeX、AI Chat、多用户
- ResuMate：Before/After Diff、ATS Score、LaTeX PDF
- ResumeCraftr：Resume Parse、JD Tailor、RAG、PDF

因此：

## 不应把这些当核心差异

```text
PDF
Typst
BYOK
Diff
Apply / Reject
Master Resume
多模型
模板
不编造
```

这些仍然值得做，但属于 **产品基本功 / 工程实现**。

## 真正应保留的产品特色

1. **岗位要求与当前已确认材料之间的证据映射**
2. **严格区分“足够证据 / 部分证据 / 当前材料无证据”**
3. **AI 修改必须受真实事实约束**
4. **用户可定义自己的优化策略，但不破坏核心协议**
5. **多个 JD 使用后，自然形成长期求职方向洞察**

> 不为了差异而差异。
>
> 差异只服务于“更可信、更好用、更符合真实求职”。

---

# 3. 最终用户主链路（冻结）

## 3.1 第一次使用

```text
打开产品
  ↓
注册 / 登录
  ↓
上传简历
  ↓（后台自动解析）
粘贴目标 JD
  ↓
[开始分析]
  ↓
岗位分析结果
  ↓
修改简历
  ↓
预览
  ↓
导出 PDF
```

### 第一次价值出现前禁止新增步骤

不允许要求用户：

```text
先配置 Provider
→ 设置 Prompt
→ 创建 Profile
→ 选择解析模式
→ 手动 Parse Resume
→ 手动 Parse JD
→ 手动 Embedding
→ 手动 Generate Match
```

---

## 3.2 再次使用

已有简历后：

```text
选择简历
   +
粘贴新 JD
   ↓
开始分析
```

目标：

> **第二次使用必须明显比第一次更快。**

---

# 4. 用户真正需要理解的概念只有 4 个

| 用户概念 | 含义 |
|---|---|
| 我的简历 | 用户当前已确认并提供的简历材料，不代表现实世界中的全部能力 |
| 目标岗位 | 当前要投递的 JD |
| 优化建议 | 为什么值得改、改哪里 |
| 优化版本 | 针对该岗位形成的新简历 |

用户不需要认识：

```text
Career Evidence
Fact Source
Evidence Graph
Requirement Model
Embedding
Vector Search
Prompt Snapshot
Provider Adapter
Typst Renderer
Task Type
Schema
```

这些全部属于 **内部实现**。

---

# 5. 后台核心链路

```text
Resume
  ↓
Resume Parsing
  ↓
Current Confirmed Resume Material

JD
  ↓
JD Requirement Parsing
  ↓
Requirement Model

Current Confirmed Resume Material
       +
Requirement Model
       ↓
Evidence Matching
       ↓
Gap Analysis
       ↓
Optimization Suggestions
       ↓
User Edit / AI Rewrite
       ↓
Resume Version
       ↓
Typst Renderer
       ↓
PDF
```

---

# 6. 核心分析模型

## 6.1 用户看到的是关系，不是神秘分数

核心关系：

```text
岗位要求
   ↕
当前已确认材料中的证据
```

例如：

```text
岗位要求：
熟悉 Redis，并具备缓存设计经验

当前材料：
只写“使用 Redis”

结论：
存在相关证据，但不足以完整支持“缓存设计经验”

建议：
建议用户核对并完善已有 Redis 内容；新增使用场景前必须先确认真实事实
```

---

## 6.2 三类材料证据状态

内部正式状态：

```text
MATCHED
PARTIAL_EVIDENCE
NO_EVIDENCE
```

前台统一使用“已有优势 / 建议完善 / 当前材料未体现”，不展示内部枚举。

### A. MATCHED：当前材料有足够证据

```text
当前材料存在通过冻结 ResumeVersion 校验的证据，且足以支持完整岗位要求。
```

### B. PARTIAL_EVIDENCE：当前材料只有部分证据

```text
当前材料存在与岗位要求直接相关的证据，但不足以完整支持要求。
```

这只描述材料的支持强度，不代表“用户现实中确实有该经历但没写清楚”。后续 AI 也不能据此增加证据中不存在的能力、技术、数字或成果。

### C. NO_EVIDENCE：当前材料没有支持证据

```text
当前材料中没有找到支持岗位要求的证据。
```

这不代表用户没有相应能力，也不允许 AI 把岗位要求硬加入简历。真正判断“用户有经历但没有写出来”，必须来自用户补充 / 确认或独立事实来源；该交互不属于 Phase 3。

---

# 7. AI 修改原则

AI 不应直接：

```text
Resume + JD
   ↓
自由重写
```

而应：

```text
岗位要求
   +
真实经历
   +
平台真实性约束
   +
用户偏好
   +
本次自定义要求
   ↓
受约束改写
```

## AI 可以做

- 重写表达
- 精简
- 重排
- 强调岗位相关内容
- 删除无关内容
- 改善技术表达
- 调整 Section 顺序

## AI 不可以做

- 添加不存在的技术
- 添加不存在的经历
- 编造量化结果
- 修改教育 / 公司 / 日期等事实
- 为了匹配 JD 强行增加能力

---

# 8. 缺少事实时的正确交互

不要让用户提前维护复杂 Fact Lock。

系统自动从简历构建事实。

当 AI 需要新事实时再询问：

```text
这个岗位重视“高并发经验”，
但你的现有材料中没有明确相关证据。

你是否确实有这方面经历？

[补充真实经历]
[没有]
```

这比：

```text
请先维护 Career Vault
请锁定每一个 Fact
```

更简单。

---

# 9. Workspace：V2 核心页面

## 推荐：两栏为主，而不是永久三栏

```text
┌────────────────────────────────────────────┐
│ ← 字节 · Java 后端        预览 / 导出 PDF │
├──────────────┬─────────────────────────────┤
│ 优化建议      │          简历编辑器          │
│              │                             │
│ 建议完善      │      Section / Bullet       │
│ 已有优势      │      Inline AI              │
│ 当前材料未体现│      Manual Edit            │
│              │                             │
└──────────────┴─────────────────────────────┘
```

Preview：

```text
编辑 | 预览
```

或右侧 Drawer / 独立 Preview 模式。

> 不要为了展示 Typst 而永久占据 1/3 页面。

---

# 10. Workspace 信息优先级

第一屏只展示：

```text
匹配情况：良好

建议完善：3
已有优势：5
暂未覆盖：2
```

核心列表：

```text
建议完善
────────────────────

Redis 相关证据不足以支持缓存设计经验

岗位：
要求 Redis 缓存设计经验

当前材料：
只出现“使用 Redis”

证据情况：
与要求相关，但不足以支持缓存设计经验

[查看原文] [优化这段]
```

不要第一屏展示：

```text
ATS Score 87
Evidence Score 71
Keyword Coverage 84
Impact 79
Semantic Match 92
```

> **事实和证据 > 神秘评分。**

---

# 11. 编辑体验

## 11.1 普通编辑

用户可以直接修改：

```text
教育经历
实习经历
项目经历
技能
自定义 Section
```

支持：

- 自动保存
- Undo / Redo
- 恢复本次优化开始前
- Section 拖拽
- Bullet 编辑

## 11.2 上下文 AI

不要巨大 Chatbot 作为主界面。

选中一段内容：

```text
✨ 岗位定向优化
精简
强化技术深度
突出成果
自定义要求
```

AI 返回：

```text
原文
↓
建议版本
↓
为什么这样改

[拒绝] [重新生成] [采纳]
```

---

# 12. Diff 是基本体验，不是产品卖点

必须支持：

```diff
- 负责后台接口开发
+ 基于 Spring Boot 完成核心业务接口开发
```

Diff 用于：

- 提升信任
- 降低修改成本
- 判断 AI 是否过度修改
- 支持 Apply / Reject

但不要把 Diff 当“核心创新”。

---

# 13. 简历版本模型

内部可使用：

```text
Base Resume / Master Resume
        │
        ├── 字节 · Java 后端
        ├── 腾讯 · 后端开发
        └── AI Engineer
```

前台不必强迫用户理解 “Master Resume”。

可以直接叫：

```text
我的简历
Java 后端
AI 工程师
通用简历
```

岗位版本：

```text
Java 后端
├── 字节 · Java 后端
├── 腾讯 · 后端开发
└── 美团 · Java
```

原则：

- 岗位版本不自动污染原始简历
- 用户可显式同步回原简历
- 每次优化任务保留来源与配置快照

---

# 14. Typst 的定位

Typst 是 **输出基础设施**，不是产品核心卖点。

推荐：

```text
Structured Resume JSON
        ↓
Visual Editor
        ↓
Typst Template
        ↓
Preview / PDF
```

Structured Resume JSON 是唯一业务数据源。

输出：

- PDF：投递
- Markdown：迁移 / 自维护
- JSON：完整备份 / 再导入

V2 模板只做：

```text
Classic
Modern
Minimal
```

不要做模板商城。

---

# 15. 首页（Home）

首页只解决：

1. 开始一次新优化
2. 继续最近任务
3. 查看自己的简历

```text
针对新岗位优化简历

[ 粘贴 JD............................ ]

简历：[Java 后端 ▼]
优化偏好：[默认 ▼]

                         [开始分析]


最近优化
────────────────
字节 · Java 后端
腾讯 · 后端开发
美团 · Java


我的简历
────────────────
Java 后端
AI 工程师
```

删除：

- Dashboard 指标墙
- ProcessStepper
- 当前状态 Card 重复
- 岗位库
- AI 历史独立模块
- 多个“下一步”说明

---

# 16. 最终信息架构

一级导航建议最多：

```text
首页
我的简历
```

可选：

```text
优化记录
```

但“优化记录”也可以直接放首页。

账号菜单属于账号区域，不新增顶部一级导航；当前最小设置入口为：

```text
账户设置
AI 设置
────────
退出登录
```

账户设置内按类别导航，当前只提供“个人资料”和“AI 设置”。个人资料仅允许编辑显示名称（nickname）；用户名和邮箱作为账户标识暂不修改。

---

# 17. 长期多 JD 洞察

这是 **P1 附加价值**，不能进入第一次使用主流程。

用户自然完成多个岗位分析：

```text
JD A
JD B
JD C
...
```

系统后台积累：

```text
Requirement Aggregation
        ↓
Target Track
        ↓
Evidence Coverage
```

达到一定数量后再提示：

```text
你最近分析了 8 个 Java 后端岗位。

这些岗位有一些共同要求。

[查看方向洞察]
```

---

# 18. 求职方向洞察

例如：

```text
Java 后端方向

分析岗位：15

常见要求
────────────────
Spring Boot   14/15
MySQL         13/15
Redis         12/15
MQ             9/15
JVM            8/15
Docker         7/15
```

结合当前已确认材料：

```text
Spring Boot   当前材料有足够证据
MySQL         当前材料有足够证据
Redis         当前材料有部分证据
MQ            当前材料未体现
JVM           当前材料有部分证据
Docker        当前材料有足够证据
```

最终告诉用户：

```text
优先完善材料
- Redis
- 微服务

待用户确认或长期补充
- MQ
- JVM
```

这不是为了“差异化”，而是解决真实秋招问题：

> **多个岗位看下来，当前材料应优先完善什么，哪些要求还需要我确认或长期补充？**

---

# 19. 多 JD 功能不能变成复杂流程

错误：

```text
先创建 Target Track
→ 导入 10 个 JD
→ 配置 Career Vault
→ 再开始优化
```

正确：

```text
单 JD 正常使用
      ↓
系统自然积累
      ↓
达到一定数据量后
      ↓
自动产生方向洞察
```

单 JD 永远是主产品。

---

# 20. 用户自定义能力

核心原则：

> **架构高度可扩展，默认产品高度克制。**

## Level 1：普通用户

只看到：

```text
选择简历
粘贴 JD
优化偏好：默认

[开始分析]
```

## Level 2：经常使用

可以使用：

```text
Profile
Rules
Template
Language
Page Limit
```

## Level 3：高级用户

高级设置：

```text
Provider
Model
BYOK
Custom Prompt
Custom Rules
```

不向普通用户暴露：

```text
Temperature
Top P
Embedding Model
Top K
Schema
System Internal Prompt
```

---

# 21. Prompt / Policy 设计

## Prompt 分层

```text
Platform Guardrails
        +
System Default Strategy
        +
User Profile / Rules
        +
Task Custom Instruction
        +
Resume / JD Data
```

## 平台不可被用户覆盖

- 不编造事实
- 权限规则
- Schema
- 安全指令
- 数据边界

## 用户可以控制

- 风格
- 篇幅
- 强调重点
- 岗位方向
- 改写方式
- 本次特殊要求

---

# 22. Profile 的正确定位

Profile 不只是 Prompt Preset。

例如：

```text
Java 后端

优化偏好
- 强调后端工程能力
- 项目最多 3 条
- 弱化 AI 内容

模型
- xxx

模板
- Classic
```

长期使用后可关联：

```text
Java 后端方向洞察
```

但不要要求用户第一次就创建 Profile。

---

# 23. AI Provider / BYOK

支持每个用户配置：

```text
Provider
Base URL
API Key
Model
```

优先支持 OpenAI-Compatible。

架构：

```text
Resume Business
      ↓
   AI Gateway
      ↓
 Provider Adapter
```

统一处理：

- Timeout
- Rate Limit
- Invalid Key
- Model Not Found
- Provider Failure
- Schema Error
- Retry
- Error Mapping

---

# 24. Credential 安全

必须：

- API Key 服务端加密
- 前端只显示掩码
- 支持测试 / 替换 / 删除
- 日志禁止完整记录
- 删除账号时清理 Credential
- HTTPS

自定义 Base URL 必须防 SSRF：

- 禁止 localhost
- 禁止私网地址
- 禁止 Metadata Endpoint
- 控制 Redirect
- Timeout
- Response Size

---

# 25. 多用户基础设施

所有资源必须显式归属：

```text
User
├── Resume
├── ResumeVersion
├── JobTarget
├── OptimizationTask
├── PromptProfile
├── RulesProfile
├── AIProviderCredential
└── ExportArtifact
```

读取：

```text
current_user + resource_id
```

禁止：

```text
只按 resource_id 查询并返回
```

Object Storage：

```text
users/{user_id}/
├── resumes/
├── temp/
└── exports/
```

---

# 26. Optimization Task 快照

一次优化任务必须保存：

```text
Resume Version
JD
Prompt Version
Rules Snapshot
Provider
Model
Template Version
Created At
```

原因：

- 用户后续修改 Prompt 不影响历史解释
- 用户后续换模型不影响历史
- 可以复现为什么当时得到这个结果

---

# 27. 可靠性

## 自动保存

```text
已保存 ✓
正在保存...
保存失败 · 重试
```

## 异步任务

不要伪造百分比。

使用真实 Stage：

```text
正在读取岗位要求
正在检查简历内容
正在生成修改建议
```

用户可以离开页面。

回来后任务仍然存在。

## 错误恢复

原则：

> **任何一步失败，都不能要求用户重新做前面的工作。**

例如：

```text
岗位分析没有完成

你的简历与 JD 已保存。

[重试]
```

---

# 28. 导出前检查

PDF 导出前做轻量检查：

- 是否超过页数
- 联系方式是否缺失
- Typst 是否排版溢出
- 是否有未确认 AI 修改
- 是否编译成功

不要新增独立“ATS 检查中心”。

---

# 29. AI 安全

Resume 和 JD 均视为：

```text
Untrusted Content
```

Prompt 结构：

```text
System / Platform Instruction → Trusted
User Strategy                → Controlled
Resume / JD                  → Untrusted Data
```

防止 JD / PDF 中出现：

```text
Ignore previous instructions...
```

被模型当成指令。

关键任务继续使用 Structured Output / Schema 校验。

---

# 30. 隐私与数据生命周期

支持：

- 删除原始简历
- 删除岗位版本
- 删除优化任务
- 删除导出 PDF
- 删除 API Key
- 导出用户数据
- 删除账号全部数据

删除必须覆盖：

```text
Database
Object Storage
Credential
Derived Artifacts
```

---

# 31. Usage 与观测

AI Usage：

```text
provider
model
request_count
input_tokens
output_tokens
latency
success / failure
```

产品指标优先：

```text
上传成功率
解析成功率
分析成功率
建议采纳率
PDF 导出率
首次价值耗时
开始优化 → 导出 PDF 转化率
```

---

# 32. 视觉原则

目标：

> **克制、专业、可信、内容驱动。**

推荐：

- 白 / 近白背景
- 浅灰边框
- 6–10px 圆角
- 极少阴影
- 一个主色
- 状态色只用于重要反馈
- Typography + Spacing 建立层级

避免：

- Card 套 Card
- 18–22px 大圆角
- 大面积渐变
- Shadow everywhere
- Tag everywhere
- Dashboard 指标墙
- “AI”出现在每一个标题

---

# 33. 文案原则

全部使用用户语言。

```text
简历资产
→ 我的简历

AI 历史
→ 优化记录

生成岗位优化建议
→ 优化这段

MATCH_ANALYSIS
→ 分析岗位

NO_EVIDENCE
→ 当前材料未体现

PARTIAL_EVIDENCE
→ 当前材料有相关证据，建议完善
```

---

# 34. Landing Page

只表达真实价值：

```text
为每一个岗位，准备更合适的简历

上传已有简历，粘贴目标 JD，
看清哪里匹配、哪里值得修改，
最终生成一份可以直接投递的 PDF。

[开始优化]
```

价值点最多三个：

```text
岗位定向
根据真实 JD 分析

真实可控
AI 不替你编经历

直接交付
修改后生成可投递 PDF
```

不要展示：

- 7 个 AI Capability
- Parse / Embedding
- AI Result
- 后台任务类型

---

# 35. 最终产品分层

```text
第一层 · 每个人都会用
────────────────────
简历 + JD
→ 分析
→ 修改
→ PDF


第二层 · 经常使用的人
────────────────────
简历版本
历史修改
Diff
优化记录
求职方向洞察


第三层 · 高级用户
────────────────────
BYOK
Prompt
Rules
Profile
Model
Template
```

---

# 36. P0 / P1 / P2

## P0 — 必须完成

### 核心体验

- 简历上传 / 维护
- JD 输入
- 自动解析
- Evidence Matching
- Gap Analysis
- Optimization Workspace
- Manual Edit
- AI Suggest
- Diff
- Apply / Reject
- Undo / Redo
- 自动保存
- Resume Version
- Typst Preview
- PDF Export

### 安全与可信

- 真实事实约束
- 不编造经历
- 原始 JD 保留
- 用户数据隔离
- 文件隔离
- Credential 加密
- AI Gateway
- Schema 校验
- Prompt Injection 边界

## P1 — 应该完成

- BYOK UI
- Prompt Profile
- Rules
- Optimization Profile
- 多模板
- Markdown / JSON Export
- Prompt / Config Snapshot
- Usage Tracking
- 数据导出 / 删除
- 多 JD 求职方向洞察
- Demo Account

## P2 — 后续再做

- 用户自定义 Typst Template
- 多 Provider Profile
- 更复杂 Track Analytics
- 插件式 Analyzer
- 多模型自动路由

---

# 37. 明确不做

```text
招聘网站爬虫
自动投递
Cover Letter 大模块
AI Chatbot 主界面
岗位库
复杂 ATS 分数体系
模板商城
插件市场
社交功能
几十种配置项
```

---

# 38. 推荐实施顺序

```text
Phase 1
信息架构 + 主链路简化
        ↓
Phase 2
Resume / ResumeVersion / JobTarget / OptimizationTask
        ↓
Phase 3
Evidence Matching + Gap Analysis
        ↓
Phase 4
Optimization Workspace + Editor + Auto Save
        ↓
Phase 5
AI Suggest + Diff + Apply/Reject + User Policy
        ↓
Phase 6
Typst + Preview + PDF
        ↓
Phase 7
BYOK + Provider Gateway + Credential Security
        ↓
Phase 8
视觉统一 + Landing + Loading / Error / Empty
        ↓
Phase 9
Multi-JD Insight + Observability + E2E + Demo
```

---

# 39. 关键验收标准

## 用户体验

- 第一次使用无需理解 Parse / Embedding / Prompt / Provider
- 上传简历后无需手动解析
- 粘贴 JD 后一键开始分析
- 分析结果先告诉用户“该改什么、为什么”
- AI 修改均可看 Diff、Apply/Reject、Undo
- 编辑自动保存
- Preview 与 PDF 基本一致
- 错误不会让用户重新做前置步骤

## 简洁性

- 一级导航 ≤ 3
- 默认界面不展示高级配置
- 不出现岗位库
- 不出现 AI 历史技术分类
- 不展示内部技术任务名
- 不为了多 JD 增加第一次使用步骤

## 可信性

- AI 不新增未验证事实
- 能区分“当前材料足够支持 / 只有部分证据 / 当前材料未体现”
- 不把当前材料之外的能力或经历判断为事实
- 原始 JD / 原始 Resume 信息保留
- 修改有来源和原因

## 扩展性

- 用户可 BYOK
- 用户可自定义 Prompt / Rules / Profile
- 系统默认策略可直接使用
- 用户配置不能破坏核心 Schema 和真实性边界
- 历史任务保存配置快照

## 安全

- API Key 加密
- Base URL 防 SSRF
- user_id 隔离
- Object Storage 隔离
- Prompt Injection 防护
- 日志脱敏
- 可删除用户数据

---

# 40. 最终内部架构

```text
┌──────────────────────────────────────┐
│ 用户体验层                           │
│ Home / Resume / Optimization        │
│ Editor / Diff / Preview / Export    │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│ 用户策略层                           │
│ Profile / Prompt / Rules            │
│ Model / Template                    │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│ 业务层                               │
│ Resume / JobTarget / Optimization   │
│ Evidence / Gap / ResumeVersion      │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│ AI 能力层                            │
│ AI Gateway / Provider / Schema      │
│ Usage / Retry / Error Mapping       │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│ 基础设施层                           │
│ PostgreSQL / pgvector / Redis       │
│ MinIO / Async Task / Typst / Auth   │
│ Security / Observability            │
└──────────────────────────────────────┘
```

---

# 41. 最终产品判断标准

任何新增功能进入 V2 前，必须回答：

```text
1. 它是否让“JD → 修改 → PDF”更顺？
2. 它是否减少用户认知成本？
3. 它是否提高真实性 / 可控性？
4. 它是否解决真实求职问题？
5. 它是否可以隐藏到高级设置而不打扰普通用户？
```

若大部分答案为否：

> **不做。**

---

# 42. 最终决策摘要

V2 主链路冻结为：

```text
我的简历
   +
目标 JD
   ↓
岗位分析
   ↓
看清：
已有优势
建议完善
当前材料未体现
   ↓
用户自行编辑
+
AI 受真实事实约束辅助修改
   ↓
岗位优化版本
   ↓
Typst
   ↓
PDF
```

长期使用后自然增加：

```text
多个真实 JD
   ↓
岗位要求聚合
   ↓
求职方向洞察
   ↓
区分：
当前材料应该怎么完善
哪些要求仍需用户确认或长期补充
```

## 最终原则

> **不为了差异而差异。**
>
> **不为了架构完整增加用户步骤。**
>
> **不把内部高级概念变成产品概念。**
>
> **复杂能力留在实现层，简单决策留给用户。**
>
> **用户第一次只需要：上传简历、粘贴 JD、开始分析。**
