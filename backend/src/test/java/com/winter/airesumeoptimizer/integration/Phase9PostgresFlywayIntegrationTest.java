package com.winter.airesumeoptimizer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.infra.ai.AiUsageMetrics;
import com.winter.airesumeoptimizer.module.ai.usage.entity.AiUsageRecord;
import com.winter.airesumeoptimizer.module.ai.usage.mapper.AiUsageRecordMapper;
import com.winter.airesumeoptimizer.module.ai.usage.service.AiUsageRecordPersistence;
import com.winter.airesumeoptimizer.module.ai.usage.service.AiUsageRetentionService;
import com.winter.airesumeoptimizer.module.evidence.entity.EvidenceAnalysis;
import com.winter.airesumeoptimizer.module.evidence.entity.EvidenceRequirement;
import com.winter.airesumeoptimizer.module.evidence.entity.RequirementEvidence;
import com.winter.airesumeoptimizer.module.evidence.mapper.EvidenceAnalysisMapper;
import com.winter.airesumeoptimizer.module.evidence.mapper.EvidenceRequirementMapper;
import com.winter.airesumeoptimizer.module.evidence.mapper.RequirementEvidenceMapper;
import com.winter.airesumeoptimizer.module.insight.service.JobDirectionInsightService;
import com.winter.airesumeoptimizer.module.insight.vo.JobDirectionCohortVO;
import com.winter.airesumeoptimizer.module.observability.service.ProductObservabilityService;
import com.winter.airesumeoptimizer.module.optimization.controller.OptimizationTaskController;
import com.winter.airesumeoptimizer.module.optimization.entity.JobTarget;
import com.winter.airesumeoptimizer.module.optimization.entity.OptimizationTask;
import com.winter.airesumeoptimizer.module.optimization.entity.ResumeVersion;
import com.winter.airesumeoptimizer.module.optimization.mapper.JobTargetMapper;
import com.winter.airesumeoptimizer.module.optimization.mapper.OptimizationTaskMapper;
import com.winter.airesumeoptimizer.module.optimization.mapper.ResumeVersionMapper;
import com.winter.airesumeoptimizer.module.resume.entity.Resume;
import com.winter.airesumeoptimizer.module.resume.entity.ResumeParseResult;
import com.winter.airesumeoptimizer.module.resume.mapper.ResumeMapper;
import com.winter.airesumeoptimizer.module.resume.mapper.ResumeParseResultMapper;
import com.winter.airesumeoptimizer.security.AuthenticatedUser;
import com.winter.airesumeoptimizer.module.user.entity.User;
import com.winter.airesumeoptimizer.module.user.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Intentionally uses the CI PostgreSQL service rather than mocked MVC slices:
 * Flyway, composite ownership FKs, read-time Insight, and REQUIRES_NEW usage
 * persistence all execute against the production SQL dialect.
 */
@SpringBootTest(properties = "app.ai.usage.retention.enabled=true")
@ActiveProfiles("phase9-e2e")
class Phase9PostgresFlywayIntegrationTest {

    private static final String SNAPSHOT = "{\"name\":\"Integration User\",\"rawText\":\"Java\\n负责 Java 后端服务开发\"}";
    private static final String SOURCE_A_DOCUMENT = "{\"schemaVersion\":\"RESUME_DOCUMENT_V1\",\"marker\":\"SOURCE_A\"}";
    private static final String SOURCE_B_DOCUMENT = "{\"schemaVersion\":\"RESUME_DOCUMENT_V1\",\"marker\":\"SOURCE_B\"}";

    @Autowired
    private Flyway flyway;
    @Autowired
    private OptimizationTaskController optimizationTaskController;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ResumeMapper resumeMapper;
    @Autowired
    private ResumeParseResultMapper resumeParseResultMapper;
    @Autowired
    private JobTargetMapper jobTargetMapper;
    @Autowired
    private ResumeVersionMapper resumeVersionMapper;
    @Autowired
    private OptimizationTaskMapper optimizationTaskMapper;
    @Autowired
    private EvidenceAnalysisMapper evidenceAnalysisMapper;
    @Autowired
    private EvidenceRequirementMapper evidenceRequirementMapper;
    @Autowired
    private RequirementEvidenceMapper requirementEvidenceMapper;
    @Autowired
    private JobDirectionInsightService insightService;
    @Autowired
    private AiUsageRecordPersistence usageRecordPersistence;
    @Autowired
    private AiUsageRecordMapper usageRecordMapper;
    @Autowired
    private AiUsageRetentionService usageRetentionService;
    @Autowired
    private ProductObservabilityService observabilityService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void freshFlywaySchemaEnforcesOwnershipAndDerivesInsightWithoutPersistedAggregate() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("26");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'ai_usage_records' AND column_name IN ('gateway_attempt_count', 'provider_dispatch_count') AND is_nullable = 'NO' AND column_default = '1'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid WHERE t.relname = 'ai_usage_records' AND c.conname IN ('ck_ai_usage_records_gateway_attempt_count_positive', 'ck_ai_usage_records_dispatch_count_positive')",
                Integer.class)).isEqualTo(2);
        User owner = user("integration-owner");
        User other = user("integration-other");
        Resume resume = resume(owner.getId());

        for (int index = 0; index < 8; index++) {
            seedFormalTask(owner.getId(), resume.getId(), index);
        }
        seedFormalTask(other.getId(), resume(other.getId()).getId(), 0);

        JobDirectionCohortVO cohort = insightService.getInsights(owner.getId()).getCohorts().getFirst();
        assertThat(cohort.getSampleSize()).isEqualTo(8);
        assertThat(cohort.getCommonRequirements())
                .anySatisfy(requirement -> {
                    assertThat(requirement.getLabel()).isEqualTo("包含 Java 的岗位要求");
                    assertThat(requirement.getOccurrenceCount()).isEqualTo(8);
                    assertThat(requirement.getSources()).allSatisfy(source ->
                            assertThat(source.getOptimizationTaskId()).isNotNull());
                });
        assertThat(insightService.getInsights(other.getId()).getCohorts()).isEmpty();
        assertThat(observabilityService.snapshot(
                LocalDateTime.now().minusDays(30), LocalDateTime.now().plusMinutes(1))
                .getAnalysisSuccesses()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void historicalAnalysisResultUsesTaskFrozenSourceAfterCurrentCanonicalPointerMoves() {
        User owner = user("frozen-source-owner");
        Resume resume = resume(owner.getId());
        seedFormalTask(owner.getId(), resume.getId(), 0);

        OptimizationTask task = optimizationTaskMapper.selectOne(new LambdaQueryWrapper<OptimizationTask>()
                .eq(OptimizationTask::getUserId, owner.getId())
                .orderByDesc(OptimizationTask::getCreatedAt)
                .last("LIMIT 1"));
        ResumeVersion sourceA = resumeVersionMapper.selectById(task.getSourceResumeVersionId());
        sourceA.setStructuredContent(SOURCE_A_DOCUMENT);
        resumeVersionMapper.updateById(sourceA);

        ResumeVersion sourceB = new ResumeVersion();
        sourceB.setUserId(owner.getId());
        sourceB.setResumeId(resume.getId());
        sourceB.setVersionType("SOURCE");
        sourceB.setSourceType("PARSED_UPLOAD");
        sourceB.setContentStatus("READY");
        sourceB.setStructuredContent(SOURCE_B_DOCUMENT);
        sourceB.setContentRevision(0L);
        sourceB.setCreatedAt(LocalDateTime.now());
        sourceB.setUpdatedAt(LocalDateTime.now());
        resumeVersionMapper.insert(sourceB);

        ResumeParseResult currentParse = new ResumeParseResult();
        currentParse.setResumeId(resume.getId());
        currentParse.setParseStatus("SUCCESS");
        currentParse.setQualityStatus("READY");
        currentParse.setUnresolvedItems("[]");
        currentParse.setCanonicalSourceVersionId(sourceB.getId());
        currentParse.setCreatedAt(LocalDateTime.now());
        currentParse.setUpdatedAt(LocalDateTime.now());
        resumeParseResultMapper.insert(currentParse);

        var result = optimizationTaskController.getAnalysisResult(
                task.getId(),
                new TestingAuthenticationToken(
                        new AuthenticatedUser(owner.getId(), owner.getUsername()), "phase9-test"));

        assertThat(result.getData().getSourceCanonicalDocument()).isEqualTo(SOURCE_A_DOCUMENT);
        assertThat(result.getData().getSourceCanonicalDocument()).doesNotContain("SOURCE_B");
        RequirementEvidence evidence = requirementEvidenceMapper.selectOne(new LambdaQueryWrapper<RequirementEvidence>()
                .eq(RequirementEvidence::getUserId, owner.getId()));
        assertThat(evidence.getSourceResumeVersionId()).isEqualTo(sourceA.getId());
    }

    @Test
    void insightKeepsFrozenSourceHistoryButImmediatelyReflectsTheWindowAndParentDeletion() {
        User owner = user("insight-lifecycle-owner");
        Resume resume = resume(owner.getId());
        for (int index = 0; index < 8; index++) {
            seedFormalTask(owner.getId(), resume.getId(), index);
        }

        assertThat(insightService.getInsights(owner.getId()).getCohorts()).hasSize(1);
        ResumeVersion target = resumeVersionMapper.selectList(new LambdaQueryWrapper<ResumeVersion>()
                        .eq(ResumeVersion::getUserId, owner.getId())
                        .eq(ResumeVersion::getVersionType, "TARGETED"))
                .getFirst();
        target.setStructuredContent("{\"target\":\"subsequent edit\"}");
        resumeVersionMapper.updateById(target);
        assertThat(insightService.getInsights(owner.getId()).getCohorts().getFirst().getSampleSize()).isEqualTo(8);

        OptimizationTask oldestTask = optimizationTaskMapper.selectList(new LambdaQueryWrapper<OptimizationTask>()
                        .eq(OptimizationTask::getUserId, owner.getId())
                        .orderByAsc(OptimizationTask::getFinishedAt))
                .getFirst();
        oldestTask.setFinishedAt(LocalDateTime.now().minusDays(181));
        optimizationTaskMapper.updateById(oldestTask);
        assertThat(insightService.getInsights(owner.getId()).getCohorts()).isEmpty();

        oldestTask.setFinishedAt(LocalDateTime.now());
        optimizationTaskMapper.updateById(oldestTask);
        assertThat(insightService.getInsights(owner.getId()).getCohorts()).hasSize(1);
        resumeMapper.deleteById(resume.getId());
        assertThat(insightService.getInsights(owner.getId()).getCohorts()).isEmpty();
    }

    @Test
    void upgradesAnIsolatedReleasedV22SchemaToTheCurrentFlywayState() {
        String schema = "phase9_upgrade_" + Long.toUnsignedString(System.nanoTime(), 36);
        try {
            Flyway releasedV22 = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("22"))
                    .load();
            releasedV22.migrate();
            assertThat(releasedV22.info().current().getVersion().getVersion()).isEqualTo("22");

            Long legacyUserId = jdbcTemplate.queryForObject(
                    "INSERT INTO " + schema + ".users (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    Long.class,
                    "phase9-legacy-user-" + schema,
                    "phase9-legacy-" + schema + "@example.invalid",
                    "phase9-test-password");
            Long legacyResumeId = jdbcTemplate.queryForObject(
                    "INSERT INTO " + schema + ".resumes (user_id, original_filename, file_type, file_size, object_key, upload_status) "
                            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    legacyUserId,
                    "legacy-java.pdf",
                    "PDF",
                    100L,
                    "resumes/legacy/legacy-java.pdf",
                    "UPLOADED");

            Flyway upgraded = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .load();
            upgraded.migrate();

            assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("26");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'resumes' AND column_name = 'display_name'",
                    Integer.class,
                    schema)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT display_name FROM " + schema + ".resumes WHERE id = ?",
                    String.class,
                    legacyResumeId)).isEqualTo("legacy-java");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = 'resumes' AND column_name = 'display_name'",
                    String.class,
                    schema)).isEqualTo("NO");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE " + schema + ".resumes SET display_name = NULL WHERE id = ?",
                    legacyResumeId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'ai_usage_records'",
                    Integer.class,
                    schema)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'ai_usage_records' AND column_name IN ('gateway_attempt_count', 'provider_dispatch_count') AND is_nullable = 'NO' AND column_default = '1'",
                    Integer.class,
                    schema)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = ? AND t.relname = 'ai_usage_records' AND c.conname IN ('ck_ai_usage_records_gateway_attempt_count_positive', 'ck_ai_usage_records_dispatch_count_positive')",
                    Integer.class,
                    schema)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'resume_parse_results' AND column_name = 'canonical_source_version_id'",
                    Integer.class,
                    schema)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'resume_parse_results' AND column_name = 'canonical_document'",
                    Integer.class,
                    schema)).isEqualTo(0);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void canonicalPointerRejectsTargetedVersionAtDatabaseBoundary() {
        User owner = user("canonical-pointer-owner");
        Resume resume = resume(owner.getId());
        seedFormalTask(owner.getId(), resume.getId(), 0);
        List<ResumeVersion> versions = resumeVersionMapper.selectList(new LambdaQueryWrapper<ResumeVersion>()
                .eq(ResumeVersion::getUserId, owner.getId())
                .eq(ResumeVersion::getResumeId, resume.getId()));
        ResumeVersion source = versions.stream()
                .filter(version -> "SOURCE".equals(version.getVersionType()))
                .findFirst()
                .orElseThrow();
        ResumeVersion targeted = versions.stream()
                .filter(version -> "TARGETED".equals(version.getVersionType()))
                .findFirst()
                .orElseThrow();

        assertThat(jdbcTemplate.update(
                "INSERT INTO resume_parse_results (resume_id, parse_status, canonical_source_version_id) VALUES (?, 'SUCCESS', ?)",
                resume.getId(),
                source.getId())).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE resume_parse_results SET canonical_source_version_id = ? WHERE resume_id = ?",
                targeted.getId(),
                resume.getId()))
                .hasMessageContaining("canonical_source_version_id must reference a SOURCE version");
    }

    @Test
    void taskDeletionKeepsOnlyUnattributedLedgerMetadataForTheRetainedUser() {
        User owner = user("usage-deletion-owner");
        Resume resume = resume(owner.getId());
        seedFormalTask(owner.getId(), resume.getId(), 0);
        OptimizationTask task = optimizationTaskMapper.selectList(new LambdaQueryWrapper<OptimizationTask>()
                        .eq(OptimizationTask::getUserId, owner.getId()))
                .getFirst();

        AiUsageRecord record = new AiUsageRecord();
        record.setUserId(owner.getId());
        record.setOptimizationTaskId(task.getId());
        record.setOperation("EVIDENCE_MATCH");
        record.setSource("SYSTEM_DEFAULT");
        record.setProvider("OPENAI_COMPATIBLE");
        record.setModel("phase9-fake");
        record.setOutcome("SUCCESS");
        record.setLatencyMs(1L);
        record.setCreatedAt(LocalDateTime.now());
        usageRecordMapper.insert(record);

        optimizationTaskMapper.deleteById(task.getId());
        AiUsageRecord retained = usageRecordMapper.selectById(record.getId());
        assertThat(retained).isNotNull();
        assertThat(retained.getUserId()).isEqualTo(owner.getId());
        assertThat(retained.getOptimizationTaskId()).isNull();
    }

    @Test
    void providerAttemptSurvivesOuterBusinessRollbackAndRawLedgerRowsExpire() {
        User owner = user("usage-owner");
        AiSelectionSnapshot selection = new AiSelectionSnapshot(
                AiSource.SYSTEM_DEFAULT,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                null,
                null,
                "https://phase9-e2e.invalid/v1",
                "phase9-fake",
                "{}",
                null);
        AiUsageRecord record = new AiUsageRecord();
        record.setUserId(owner.getId());
        record.setOperation("JOB_DESCRIPTION_PARSE");
        record.setSource("SYSTEM_DEFAULT");
        record.setProvider("OPENAI_COMPATIBLE");
        record.setModel("phase9-fake");
        record.setOutcome("SUCCESS");
        record.setLatencyMs(1L);
        record.setPromptTokens(1);
        record.setCompletionTokens(1);
        record.setTotalTokens(2);
        record.setCreatedAt(LocalDateTime.now());

        transactionTemplate.executeWithoutResult(status -> {
            usageRecordPersistence.persist(record);
            status.setRollbackOnly();
        });
        assertThat(usageRecordMapper.selectById(record.getId())).isNotNull();
        assertThat(usageRecordMapper.selectById(record.getId()).getProviderDispatchCount()).isEqualTo(1);

        AiUsageRecord expired = new AiUsageRecord();
        expired.setUserId(owner.getId());
        expired.setOperation("CREDENTIAL_TEST");
        expired.setSource("SYSTEM_DEFAULT");
        expired.setProvider("OPENAI_COMPATIBLE");
        expired.setModel("phase9-fake");
        expired.setOutcome("FAILURE");
        expired.setFailureCode("PROVIDER_UNAVAILABLE");
        expired.setLatencyMs(1L);
        expired.setCreatedAt(LocalDateTime.now().minusDays(91));
        usageRecordMapper.insert(expired);

        assertThat(usageRetentionService.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(usageRecordMapper.selectById(expired.getId())).isNull();
        assertThat(usageRecordMapper.selectById(record.getId())).isNotNull();
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username + "-" + System.nanoTime());
        user.setEmail(username + "-" + System.nanoTime() + "@example.invalid");
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    private Resume resume(Long userId) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setOriginalFilename("integration-java.pdf");
        resume.setDisplayName("integration-java");
        resume.setFileType("PDF");
        resume.setFileSize(100L);
        resume.setObjectKey("resumes/" + userId + "/integration-java.pdf");
        resume.setStorageType("LOCAL");
        resume.setUploadStatus("UPLOADED");
        resume.setCreatedAt(LocalDateTime.now());
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.insert(resume);
        return resume;
    }

    private void seedFormalTask(Long userId, Long resumeId, int index) {
        LocalDateTime completedAt = LocalDateTime.now().minusDays(index + 1);
        JobTarget target = new JobTarget();
        target.setUserId(userId);
        target.setTitle("Java 岗位 " + index);
        target.setRawJd("Java 后端岗位 " + index + "，要求熟悉 Java");
        target.setSourceType("USER_INPUT");
        target.setCreatedAt(completedAt);
        target.setUpdatedAt(completedAt);
        jobTargetMapper.insert(target);

        ResumeVersion source = new ResumeVersion();
        source.setUserId(userId);
        source.setResumeId(resumeId);
        source.setVersionType("SOURCE");
        source.setSourceType("PARSED_UPLOAD");
        source.setContentStatus("READY");
        source.setStructuredContent(SNAPSHOT);
        source.setContentRevision(0L);
        source.setCreatedAt(completedAt);
        source.setUpdatedAt(completedAt);
        resumeVersionMapper.insert(source);

        ResumeVersion targeted = new ResumeVersion();
        targeted.setUserId(userId);
        targeted.setResumeId(resumeId);
        targeted.setSourceVersionId(source.getId());
        targeted.setJobTargetId(target.getId());
        targeted.setVersionType("TARGETED");
        targeted.setSourceType("JOB_DERIVATION");
        targeted.setContentStatus("READY");
        targeted.setStructuredContent(SNAPSHOT);
        targeted.setContentRevision(0L);
        targeted.setCreatedAt(completedAt);
        targeted.setUpdatedAt(completedAt);
        resumeVersionMapper.insert(targeted);

        OptimizationTask task = new OptimizationTask();
        task.setUserId(userId);
        task.setSourceResumeVersionId(source.getId());
        task.setTargetResumeVersionId(targeted.getId());
        task.setJobTargetId(target.getId());
        task.setStatus("SUCCESS");
        task.setResumeInputSnapshot(SNAPSHOT);
        task.setJobInputSnapshot(target.getRawJd());
        task.setPromptSnapshot("{}");
        task.setRulesSnapshot("{}");
        task.setAiSourceSnapshot("SYSTEM_DEFAULT");
        task.setTemplateVersion("NOT_SELECTED");
        task.setCreatedAt(completedAt);
        task.setUpdatedAt(completedAt);
        task.setFinishedAt(completedAt);
        optimizationTaskMapper.insert(task);

        EvidenceAnalysis analysis = new EvidenceAnalysis();
        analysis.setUserId(userId);
        analysis.setOptimizationTaskId(task.getId());
        analysis.setMatchedCount(1);
        analysis.setPartialEvidenceCount(0);
        analysis.setNoEvidenceCount(0);
        analysis.setModelName("phase9-fake");
        analysis.setPromptVersion("phase9-e2e");
        analysis.setCreatedAt(completedAt);
        analysis.setUpdatedAt(completedAt);
        evidenceAnalysisMapper.insert(analysis);

        EvidenceRequirement requirement = new EvidenceRequirement();
        requirement.setUserId(userId);
        requirement.setEvidenceAnalysisId(analysis.getId());
        requirement.setRequirementText(index % 2 == 0 ? "Java" : "熟悉 Java");
        requirement.setImportance("REQUIRED");
        requirement.setMatchLevel("MATCHED");
        requirement.setConclusion("冻结材料中包含 Java。");
        requirement.setDisplayOrder(0);
        requirement.setCreatedAt(completedAt);
        evidenceRequirementMapper.insert(requirement);

        RequirementEvidence evidence = new RequirementEvidence();
        evidence.setUserId(userId);
        evidence.setEvidenceRequirementId(requirement.getId());
        evidence.setSourceResumeVersionId(source.getId());
        evidence.setSectionLabel("技能");
        evidence.setEvidenceText("Java");
        evidence.setSupportLevel("SUFFICIENT");
        evidence.setCreatedAt(completedAt);
        requirementEvidenceMapper.insert(evidence);
    }
}
