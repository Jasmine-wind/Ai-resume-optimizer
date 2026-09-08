# AGENTS.md

## Repository role

CV-Role is a frontend/backend separated repository. The root is repository orchestration only; it is not a Spring Boot project.

```text
backend/  Spring Boot backend
web/      Vue 3 frontend
docs/     long-lived product, architecture, plan, context, and operations documentation
deploy/   deployment configuration
scripts/  operational scripts
```

Do not create root-level `src/`, `pom.xml`, `.mvn/`, `mvnw`, or application code.

## Required context

Before changing the repository, read:

1. `docs/PRD.md` — authoritative V2 product and high-level architecture baseline.
2. `docs/ARCHITECTURE.md` — current implementation boundaries.
3. `docs/PLAN.md` — approved migration order and non-goals.
4. `docs/CONTEXT.md` — current capabilities, gaps, and known risks.

Product conflicts are resolved by `docs/PRD.md`. Current behavior is determined by code, migrations, configuration, and tests—not by historical Git documents.

## Current phase

Phases 1–9 are complete and each has passed its independent Final Gate. No Phase 10 has been approved; do not extend the product beyond the Phase 9 Contract without explicit instruction. Phase 8 changed only the frontend; Phase 9 preserves the Phase 1–8 business semantics, sources of truth, state machines, and security boundaries.

Do not introduce microservices, message queues, Kubernetes, crawlers, auto-apply, a job marketplace, a chatbot-first UI, a complex ATS score system, a template marketplace, or a new technology stack unless the product baseline is explicitly revised.

## Backend placement

Root package: `com.winter.airesumeoptimizer` under:

```text
backend/src/main/java/com/winter/airesumeoptimizer/
```

- `common/`: cross-module result, exception, logging, and utilities.
- `config/`: application configuration.
- `security/`: authentication, authorization, and JWT.
- `infra/`: external adapters such as AI, Embedding, Redis, and storage.
- `module/`: all business modules.

Business code belongs under `module/{module}/`. Keep controllers, services, implementations, entities, mappers, DTOs, and VOs separated by responsibility. Do not put business orchestration in `common/`, `config/`, `security/`, or `infra/`.

Database changes use Flyway migrations under `backend/src/main/resources/db/migration/`. Never reintroduce manual `db/init` schema scripts. Never edit an already-released migration to change production state; add a new migration.

Preserve these invariants:

- Queries and file access enforce current-user resource ownership.
- AI output is untrusted until parsed and technically validated.
- AI Suggest may propose unconfirmed candidate content, but it must not be treated as a user fact or written automatically; explicit user Apply is required.
- Redis is not the sole source of business truth.
- Storage access goes through the storage abstraction.
- Logs and client errors do not expose credentials or raw provider secrets.

## Frontend placement

All frontend source lives under `web/src/`:

- API clients: `api/`
- page-level components: `views/`
- reusable components: `components/`
- layouts: `layout/`
- routes: `router/`
- state: `stores/`
- types: `types/`
- utilities: `utils/`
- global styles: `styles/`

Do not put backend code or Maven files in `web/`. Do not expose Provider, Prompt, Embedding, Schema, Task Type, or other internal concepts in the default user flow unless the PRD phase explicitly calls for it.

## Documentation rules

Prefer updating existing long-lived documents over adding new ones:

- `README.md`: entry point and run instructions.
- `docs/PRD.md`: product decisions.
- `docs/ARCHITECTURE.md`: architecture and constraints.
- `docs/PLAN.md`: phase plan.
- `docs/CONTEXT.md`: current status and confirmed decisions.
- `docs/OPERATIONS.md`: deployment and operations.

Do not create iteration logs, duplicate summaries, task-list sprawl, or parallel old/new flow documents. Git history is the archive for superseded stage notes.

## Change discipline

- Inspect repository references before deleting or moving files.
- Preserve uncertain files and report the uncertainty.
- Do not perform unrelated behavior changes during cleanup or documentation work.
- Do not add a new top-level directory without explaining why existing areas cannot contain it.
- Keep local secrets, uploads, private test inputs, IDE state, build outputs, and dependencies out of Git.
- Never commit `.env`, real resumes, API keys, tokens, private server data, `target/`, `dist/`, or `node_modules/`.

## Validation

Run checks appropriate to the changed area:

```bash
cd backend && ./mvnw test
cd web && npm run build
git diff --check
```

For deployment changes, also render the relevant Compose configuration. Do not modify unrelated business code merely to make an environmental check pass; report environmental failures precisely.
