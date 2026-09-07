# 当前版本部署与运维

状态：已部署可用  
正式访问地址：`https://resume.dawn04.xyz`  
部署方式：Docker Compose 单机部署  
服务器部署目录：`/opt/ai-resume-optimizer`

说明：本文档已合并原部署文档、运维文档、运维脚本说明、安全检查清单和常见问题排查内容，是当前唯一维护的部署运维入口。

---

## 1. 部署架构

当前线上环境采用 Docker Compose 单机部署：

```text
用户浏览器
  ↓
Nginx 容器 + HTTPS
  ↓
Vue 前端静态资源
  ↓
/api 反向代理
  ↓
Spring Boot 后端容器
  ↓
PostgreSQL + pgvector 容器
Redis 容器
MinIO 容器
AI / Embedding API
```

核心服务：

| 服务 | 说明 |
|---|---|
| nginx | 前端静态资源托管、HTTPS、反向代理 `/api` |
| backend | Spring Boot 后端服务（镜像内置 Typst 编译器与 CJK 字体，用于 PDF Preview / Export） |
| postgres | PostgreSQL + pgvector 数据库 |
| redis | 缓存服务 |
| minio | 简历文件对象存储 |
| certbot | Let's Encrypt 证书申请和续期 |

渲染依赖：PDF 预览与导出在后端容器内同步调用 Typst CLI。编译器版本与发布包 SHA-256 固定在 `backend/Dockerfile`（当前 v0.15.1，与 CI 一致）；升级 Typst 时必须同步回归三套内置模板、PDF checksum 确定性和渲染器版本。`APP_RENDER_TIMEOUT` 控制单次编译超时，`APP_RENDER_PREVIEW_RECEIPT_TTL` 控制签名 Preview receipt 有效期（默认 10 分钟）。导出物写入既有私有存储（本地为 `uploads/exports/`，生产为 MinIO bucket）；删除失败保留 DELETE_PENDING 元数据供用户重试，不新增容器或后台清理基础设施。

---

## 2. 服务器目录与数据持久化

服务器目录：

```text
/opt/ai-resume-optimizer/
├── backend/
├── web/
├── deploy/
│   └── nginx/
│       └── conf.d/
│           └── ai-resume.prod.conf.template
├── docker-compose.prod.yml
├── .env                 # 唯一生产运行时环境文件（不提交 Git）
├── docs/
└── README.md
```

Docker volume 持久化数据包括：

```text
postgres_data
redis_data
minio_data
backend_logs
certbot_www
letsencrypt
```

注意：

- `.env` 是生产 Compose 和 `scripts/ops/` 的统一运行时环境文件，只存在服务器本地，不提交 Git。仓库中的 `.env.production.example` 仅是模板，部署时复制为 `.env`。
- 真实 API Key、数据库密码、JWT Secret、MinIO 密钥都只放在服务器 `.env`。
- 不要随意执行 `docker compose down -v`，否则可能删除数据库、MinIO 文件和证书数据。

---

## 3. 生产环境关键配置

服务器 `.env` 中至少应包含：

```env
SPRING_PROFILES_ACTIVE=prod

POSTGRES_DB=ai_resume_optimizer
POSTGRES_USER=ai_resume
POSTGRES_PASSWORD=your-production-postgres-password
DATABASE_URL=jdbc:postgresql://postgres:5432/ai_resume_optimizer

REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=your-production-redis-password

MINIO_ENDPOINT=http://minio:9000
MINIO_ROOT_USER=ai-resume-minio
MINIO_ROOT_PASSWORD=your-production-minio-password
MINIO_ACCESS_KEY=ai-resume-minio
MINIO_SECRET_KEY=your-production-minio-password
MINIO_BUCKET=ai-resume-files
APP_STORAGE_TYPE=minio

JWT_SECRET=your-long-random-jwt-secret
JWT_EXPIRATION_MINUTES=120

# No platform Chat credential is configured. All users provide their own BYOK.
AI_TEMPERATURE=0.2
AI_TIMEOUT_SECONDS=90
AI_MAX_TOKENS=16000

# Required production BYOK encryption infrastructure.
AI_CREDENTIALS_ENABLED=true
AI_CREDENTIALS_ACTIVE_KEY_ID=v1
AI_CREDENTIALS_KEY_RING=v1=base64url-32-byte-key

EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
EMBEDDING_API_KEY=your-siliconflow-api-key
EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B
EMBEDDING_DIMENSION=1024

DEPLOY_DOMAIN=resume.dawn04.xyz
CERTBOT_EMAIL=your-email@example.com
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://resume.dawn04.xyz
```

说明：

- Docker Compose 内部服务通信使用服务名，不使用 `localhost`。
- 后端访问数据库使用 `postgres:5432`。
- 后端访问 Redis 使用 `redis:6379`。
- 后端访问 MinIO 使用 `http://minio:9000`。
- 前端通过 Nginx 同域名 `/api` 访问后端。
- HTTPS 成功后，CORS 建议只保留 `https://resume.dawn04.xyz`。
- 上传 contract 是应用文件 10 MB、multipart request 12 MB；Nginx 也限制为 12 MB，避免前端允许的 10 MB 文件被网关提前截断。
- timeout chain 保持有界：AI 90 秒、Embedding 120 秒、Typst render 30 秒，Nginx `/api` read timeout 150 秒；分析主流程本身是异步任务，不靠延长网关 timeout 假造进度。
- 生成式 Chat 为 BYOK-only；没有 ACTIVE BYOK 时新任务返回 `AI_CONFIGURATION_REQUIRED`，不会使用服务器 Chat Key。
- 生产固定 `AI_CREDENTIALS_ENABLED=true`。`AI_CREDENTIALS_KEY_RING` 必须使用 `keyId=base64url-32-byte-key;nextKeyId=...` 格式，且 `AI_CREDENTIALS_ACTIVE_KEY_ID` 必须存在于 key ring；Compose 和后端启动校验都会在缺失或非法时 fail fast。
- local/test 可以设置 `AI_CREDENTIALS_ENABLED=false`，用于无 AI 页面或 deterministic fake Provider；这不会开启任何生产 fallback。
- 轮换主密钥时先同时部署旧 / 新 key 并切换 active key；应用会在 Credential 使用时重加密且不改变 `credential_revision`。确认数据库不再引用旧 `encryption_key_version` 后才能移除旧 key。

---

## 4. 常用运维命令

进入项目目录：

```bash
cd /opt/ai-resume-optimizer
```

查看容器状态：

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps
```

查看后端日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs -f backend
```

查看 Nginx 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs -f nginx
```

查看 PostgreSQL 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs -f postgres
```

查看 Redis 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs -f redis
```

查看 MinIO 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs -f minio
```

常规应用重启（默认只重启 Backend 和 Nginx）：

```bash
scripts/ops/restart-services.sh
# 或显式指定：
scripts/ops/restart-services.sh backend nginx
```

直接重启后端或 Nginx：

```bash
docker compose -f docker-compose.prod.yml --env-file .env restart backend
docker compose -f docker-compose.prod.yml --env-file .env restart nginx
```

数据服务需要单独、谨慎操作，并在重启后检查 health 与 smoke：

```bash
scripts/ops/restart-services.sh redis
scripts/ops/restart-services.sh minio
scripts/ops/restart-services.sh postgres
```

不要将整个 Compose project 无差别执行 `restart`。`minio-init` 是 fresh startup 时运行一次的 one-shot bucket 初始化服务，`certbot` 是按需运行的证书命令；两者都不应作为常规 restart 目标。

---

# 5. Git Pull 后更新 Docker 的流程

## 5.1 基本原则

服务器不需要重新 `git clone`。

正常更新流程：

```text
本地修改代码
↓
本地 commit + push
↓
服务器 git pull
↓
根据改动范围重新 build 对应 Docker 服务
↓
检查容器状态和日志
↓
浏览器强制刷新验证
```

不要动 `.env`，不要执行 `down -v`。

---

## 5.2 本地提交代码

在本地项目目录执行：

```bash
git status
git add .
git commit -m "fix: describe your change"
git push
```

---

## 5.3 服务器拉取最新代码

登录服务器：

```bash
ssh root@your-server-ip
```

进入项目目录：

```bash
cd /opt/ai-resume-optimizer
```

查看服务器是否有未提交改动：

```bash
git status
```

拉取最新代码：

```bash
git pull
```

---

## 5.4 根据修改范围重新构建 Docker

### 只修改后端

例如修改了：

```text
backend/src/
backend/pom.xml
backend/src/main/resources/
```

执行：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build backend
```

查看日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs -f backend
```

---

### 只修改前端

例如修改了：

```text
web/src/
web/package.json
web/vite.config.ts
web/Dockerfile
```

如果前端由 Nginx 镜像构建并托管，执行：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build nginx
```

浏览器强制刷新：

```text
Ctrl + F5
```

如果仍显示旧内容，可以用无痕窗口访问。

---

### 前后端都修改了

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build backend nginx
```

或者直接：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

---

### 修改了 docker-compose.prod.yml

先检查配置：

```bash
docker compose -f docker-compose.prod.yml --env-file .env config
```

没有报错后执行：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

---

### 修改了 Nginx 配置

例如修改了：

```text
deploy/nginx/conf.d/ai-resume.prod.conf.template
deploy/nginx/entrypoint/40-configure-production.sh
```

先检查 Nginx 配置：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec nginx nginx -t
```

配置通过后重启：

```bash
docker compose -f docker-compose.prod.yml --env-file .env restart nginx
```

如果 Nginx 配置是构建时复制进镜像，也可以：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build nginx
```

---

### 修改了 .env

如果修改的是后端运行时配置，例如：

```text
AI_CREDENTIALS_ENABLED
AI_CREDENTIALS_ACTIVE_KEY_ID
AI_CREDENTIALS_KEY_RING
EMBEDDING_API_KEY
APP_CORS_ALLOWED_ORIGIN_PATTERNS
JWT_EXPIRATION_MINUTES
```

重启或重建后端：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build backend
```

如果修改的是前端构建时变量，例如：

```text
VITE_API_BASE_URL
```

需要重新构建 nginx / 前端镜像：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build nginx
```

注意：Vite 的 `VITE_*` 变量通常是构建时写入静态文件的，只重启容器不一定生效。

---

## 5.5 更新后检查

查看服务状态：

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps
```

查看后端启动日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=200 backend
```

查看 Nginx 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=100 nginx
```

浏览器访问：

```text
https://resume.dawn04.xyz
```

核心功能简单验收：

```text
1. 登录
2. 上传简历
3. 简历解析
4. 目标岗位输入
5. 匹配分析
6. 优化建议
7. 局部改写
8. AI 历史
```

---

## 5.6 严禁随意执行的命令

不要随意执行：

```bash
docker compose -f docker-compose.prod.yml --env-file .env down -v
```

原因：

```text
-v 会删除 Docker volume
可能导致 PostgreSQL 数据丢失
可能导致 Redis 数据丢失
可能导致 MinIO 文件丢失
可能导致证书数据丢失
```

如果只是更新代码，不需要 `down -v`。

---

## 6. HTTPS 配置说明

当前正式域名：

```text
https://resume.dawn04.xyz
```

Nginx 配置文件：

```text
deploy/nginx/conf.d/ai-resume.prod.conf.template
deploy/nginx/entrypoint/40-configure-production.sh
```

生产模板会在证书存在时启用 HTTPS 和 HTTP → HTTPS redirect；首次无证书时使用 HTTP fallback，以便完成 ACME challenge。

证书目录：

```text
/etc/letsencrypt/live/resume.dawn04.xyz/
```

检查证书：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec nginx   ls -lah /etc/letsencrypt/live/resume.dawn04.xyz
```

正确应包含：

```text
fullchain.pem
privkey.pem
```

测试 HTTPS：

```bash
curl -I https://resume.dawn04.xyz
```

测试 HTTP 是否跳转 HTTPS：

```bash
curl -I http://resume.dawn04.xyz
```

---

## 7. 证书首次申请与续期

首次部署没有证书时，Nginx 会安全地以 HTTP fallback 启动；申请成功后重启 Nginx，entrypoint 会切换到 HTTPS 配置：

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build nginx
docker compose -f docker-compose.prod.yml --env-file .env --profile certbot run --rm certbot
docker compose -f docker-compose.prod.yml --env-file .env restart nginx
```

测试证书续期：

```bash
cd /opt/ai-resume-optimizer

docker compose -f docker-compose.prod.yml --env-file .env --profile certbot run --rm certbot renew --dry-run
```

如果测试通过，可以配置 crontab（续期成功后重启 Nginx，使 entrypoint 重新读取证书）：

```bash
crontab -e
```

添加：

```cron
0 3 * * * cd /opt/ai-resume-optimizer && docker compose -f docker-compose.prod.yml --env-file .env --profile certbot run --rm certbot renew && docker compose -f docker-compose.prod.yml --env-file .env restart nginx
```

---

## 8. 数据库查看与排查

查看表：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec postgres sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\dt"'
```

查看用户表：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec postgres sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select id, username, email, created_at from users order by id desc limit 10;"'
```

查看 Flyway 迁移记录：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec postgres sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select installed_rank, version, description, success, installed_on from flyway_schema_history order by installed_rank desc limit 20;"'
```

---

## 9. MinIO 检查

查看 MinIO 容器状态：

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps minio
```

查看 MinIO 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=100 minio
```

如果上传文件失败，重点检查：

```text
MINIO_ENDPOINT
MINIO_ACCESS_KEY
MINIO_SECRET_KEY
MINIO_BUCKET
APP_STORAGE_TYPE
```

后端访问 MinIO 应使用：

```env
MINIO_ENDPOINT=http://minio:9000
```

不要写成：

```env
MINIO_ENDPOINT=http://localhost:9000
```

---

## 10. Redis 检查

查看 Redis 容器状态：

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps redis
```

查看 Redis 日志：

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=100 redis
```

如果缓存异常，重点检查：

```text
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD
APP_REDIS_CACHE_ENABLED
```

---

## 11. 常见问题排查

### 11.1 前端能打开，但接口失败

检查浏览器 Network：

```text
Request URL 是否为 https://resume.dawn04.xyz/api/...
是否出现 /api/api/...
状态码是 404 / 500 / 502 / CORS
```

如果出现 `/api/api/`，说明前端 baseURL 和接口路径重复拼接了 `/api`。

---

### 11.2 修改前端后页面还是旧的

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build nginx
```

浏览器强制刷新：

```text
Ctrl + F5
```

---

### 11.3 后端启动失败

```bash
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=300 backend
```

常见原因：

```text
数据库连接失败
Redis 密码错误
MinIO 配置错误
JWT_SECRET 缺失
AI_CREDENTIALS_ACTIVE_KEY_ID 或 AI_CREDENTIALS_KEY_RING 缺失 / 格式错误（生产启动 fail fast）
Flyway 迁移失败
```

---

### 11.4 Nginx 启动失败

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec nginx nginx -t
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=100 nginx
```

常见原因：

```text
证书路径错误
Nginx 配置语法错误
443 端口被占用
后端服务名写错
```

---

### 11.5 HTTPS 访问失败

检查证书：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec nginx   ls -lah /etc/letsencrypt/live/resume.dawn04.xyz
```

检查 Nginx：

```bash
docker compose -f docker-compose.prod.yml --env-file .env exec nginx nginx -t
```

检查 443 端口：

```bash
ss -tulpn | grep ':443'
```

检查云服务器安全组是否放行 TCP 443。

---

## 12. 运维脚本与基础备份

仓库 `scripts/ops/` 保留当前生产运维入口：

```text
backup-postgres.sh   PostgreSQL 备份
restore-postgres.sh  PostgreSQL 恢复
backup-minio.sh      MinIO 备份
restore-minio.sh     MinIO 恢复（显式 target + confirm）
backup-uploads.sh    local 存储目录备份
restart-services.sh  Compose 服务重启
show-logs.sh         汇总查看服务日志
smoke-check.sh       无副作用在线检查
```

执行前先阅读脚本参数并确认服务器目录、环境文件和目标备份位置。恢复脚本会修改数据，必须先做现状备份并在维护窗口执行。

至少定期备份：

```text
.env
docker-compose.prod.yml
deploy/nginx/conf.d/ai-resume.prod.conf.template
deploy/nginx/entrypoint/40-configure-production.sh
PostgreSQL 数据
MinIO 文件
```

备份 `.env` 和部署配置：

```bash
mkdir -p /opt/ai-resume-optimizer/backups/config
chmod 700 /opt/ai-resume-optimizer/backups/config

cp /opt/ai-resume-optimizer/.env /opt/ai-resume-optimizer/backups/config/.env.$(date +%F)
cp /opt/ai-resume-optimizer/docker-compose.prod.yml /opt/ai-resume-optimizer/backups/config/docker-compose.prod.yml.$(date +%F)
cp /opt/ai-resume-optimizer/deploy/nginx/conf.d/ai-resume.prod.conf.template /opt/ai-resume-optimizer/backups/config/ai-resume.prod.conf.template.$(date +%F)
cp /opt/ai-resume-optimizer/deploy/nginx/entrypoint/40-configure-production.sh /opt/ai-resume-optimizer/backups/config/40-configure-production.sh.$(date +%F)
chmod 600 /opt/ai-resume-optimizer/backups/config/*
```

PostgreSQL、MinIO 和 local uploads 的实际备份命令统一见下一节；不要绕过脚本直接重定向 `pg_dump`，否则会跳过对象数量、空文件和 checksum 验证。

---

## 13. 备份与恢复

生产 Compose 和 `scripts/ops/` 默认读取 `.env`；可用 `ENV_FILE=/path/to/.env` 显式覆盖。备份目录默认是 Git 之外的 `backups/`，脚本使用 `umask 077` 并设置目录 / 文件权限。不要把 `.env`、dump、对象或日志复制到 issue 或聊天记录。

PostgreSQL 备份脚本会验证 SQL 头、schema 对象、源库对象数量和 SHA-256 sidecar：

```bash
cd /opt/ai-resume-optimizer
./scripts/ops/backup-postgres.sh
```

MinIO 备份通过 S3 API 镜像真实 bucket objects，不复制运行中的内部 `/data`；会生成 `objects/`、`METADATA` 和 `SHA256SUMS`：

```bash
./scripts/ops/backup-minio.sh
```

仅在 `APP_STORAGE_TYPE=local` 时额外备份本地 uploads：

```bash
./scripts/ops/backup-uploads.sh
```

恢复是 destructive operation。必须先取得当前备份，并在维护窗口明确指定 target 与确认开关。PostgreSQL 目标默认必须为空；脚本使用 `ON_ERROR_STOP` + 单事务，验证文件 / checksum / SQL 格式，并在恢复后验证数据库对象：

```bash
RESTORE_ALLOW_NONEMPTY=no \
  ./scripts/ops/restore-postgres.sh \
  backups/postgres/postgres-YYYY-MM-DD_HH-MM-SS.sql \
  --target-database "$POSTGRES_DB" --confirm
```

MinIO 恢复会让目标 bucket 与备份镜像一致并删除目标中多余对象，因此必须显式指定 bucket：

```bash
./scripts/ops/restore-minio.sh \
  backups/minio/minio-data-YYYY-MM-DD_HH-MM-SS \
  --target-bucket cv-role-recovery-drill --confirm
```

恢复脚本先验证 manifest、对象数量和 checksum，再 mirror 到目标并重新读取对象验证 checksum。PostgreSQL metadata 与 MinIO object 共同构成简历事实，只恢复一边不算完成。所有 drill 使用临时数据库、临时 bucket 和 synthetic marker；禁止使用个人开发库、生产库或真实简历。

每月至少执行一次 recovery drill：源测试数据库写入 marker、源测试 bucket 上传 fixture，分别备份，恢复到隔离 target，查询 `flyway_schema_history` 与核心表（以当前 migrations 为准），并比较对象 SHA-256。至少演练备份路径不存在和篡改 dump / manifest；两者都必须在写入 target 前 fail closed。

## 14. 回滚、健康与容量

每次部署前记录当前 SHA 和 known-good SHA：

```bash
git rev-parse HEAD
docker compose -f docker-compose.prod.yml --env-file .env ps
```

code-only 回滚必须 checkout known-good commit、重新 build、通过 smoke check；不要只重启旧容器：

```bash
git checkout <known-good-sha>
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
BASE_URL=https://resume.dawn04.xyz ./scripts/ops/smoke-check.sh
```

如果发布已运行 Flyway migration，不能简单 checkout 旧 SHA。迁移按 forward-only 处理；先保留当前 PostgreSQL + MinIO 配对备份，确认旧应用是否兼容当前 schema，必要时制定向前兼容修复或经批准后从完整备份恢复。生产不执行 `flyway clean` 或未经批准的 downgrade。

基础 smoke check 不登录、不创建用户、不上传文件、不触发 AI：

```bash
BASE_URL=http://localhost ./scripts/ops/smoke-check.sh
# 生产环境显式指定：BASE_URL=https://resume.dawn04.xyz ./scripts/ops/smoke-check.sh
```

Restart contract：fresh startup 时 `minio-init` 必须完成并以 exit code 0 退出；之后只重启长期运行服务。Backend、Redis、Nginx 重启后必须重新达到 healthy 并通过 smoke。MinIO 或 PostgreSQL 重启属于数据服务维护，除 health 外还要验证应用恢复与 synthetic/维护窗口内的数据持久性；不要重启 `minio-init` 或 certbot one-shot command。

检查服务、日志和容量：

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps
docker compose -f docker-compose.prod.yml --env-file .env logs --tail=200 backend nginx postgres redis minio
df -h
docker system df
du -sh backups/* 2>/dev/null || true
```

Backend 文件日志有 Logback 14 天 / 1 GB 上限，Compose stdout/stderr 也设置了每容器 10 MB × 5 的 json-file 上限；Nginx 日志直接进入 Docker 日志。当前不自动删除旧备份，避免误删；保留策略应由 operator 先 dry-run 列出超过 30 天的文件并确认异地备份完整后再手动删除。

## 15. 部署验收清单

每次部署或更新后，至少检查：

- [ ] `docker compose ps` 所有核心服务正常。
- [ ] `https://resume.dawn04.xyz` 可以访问。
- [ ] HTTP 自动跳转 HTTPS。
- [ ] 注册登录正常。
- [ ] 简历上传正常。
- [ ] 简历解析正常。
- [ ] 目标岗位新增正常。
- [ ] 匹配分析正常。
- [ ] 优化建议正常。
- [ ] 局部改写正常。
- [ ] AI 历史正常。
- [ ] 后端日志无持续 ERROR。
- [ ] Nginx 日志无大量 502。
- [ ] 重启后数据不丢失。

---

## 16. Phase 9 非生产 Demo

Demo 是运维隔离环境，不是生产共享账号或 `DemoAccount` 领域模型。它使用合成普通 User、同一 JWT / ownership / Workspace / Typst / Storage 路径，以及确定性 in-process Provider；BYOK 和外部 AI 均关闭。

```bash
cp deploy/demo/.env.example deploy/demo/.env
# 为 DEMO_DB_PASSWORD、DEMO_JWT_SECRET、DEMO_USER_PASSWORD 写入仅用于 Demo 的值

docker compose --env-file deploy/demo/.env -f deploy/demo/docker-compose.yml up -d --build
```

默认仅绑定 `127.0.0.1:18080`。如需展示，必须由独立访问控制层显式代理；不得改为共享可写生产 Demo，也不得复用生产数据库、bucket、凭据或域名。

Demo reset 是 operator-only 操作，会删除**仅** `cv-role-demo` Compose project 的数据库和本地 Demo storage volume；脚本强制该 project 名称并且只接受 `deploy/demo/` 下的环境文件，且要求 `.env` 中明确设置确认值：

```bash
DEMO_RESET_CONFIRM=RESET_DEMO_ENVIRONMENT \
  scripts/ops/reset-demo-environment.sh deploy/demo/.env
```

不要对生产 Compose、生产 volume 或 `.env` 使用该脚本。

AI Usage 原始 attempt metadata 由应用每日按默认 90 天 retention 清理；它不包含 Resume/JD/Prompt/Output/API Key/Provider URL，不能作为产品漏斗或用户行为历史。

## 17. 当前正式入口

项目正式访问地址：

```text
https://resume.dawn04.xyz
```

仓库入口和运行状态说明统一维护在 `README.md`、`docs/CONTEXT.md` 与本文。V2 产品目标不用于推断当前线上已经具备的功能。
