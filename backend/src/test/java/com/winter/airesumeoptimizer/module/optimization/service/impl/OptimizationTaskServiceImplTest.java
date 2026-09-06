package com.winter.airesumeoptimizer.module.optimization.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.module.analysis.mapper.AiJobMatchResultMapper;
import com.winter.airesumeoptimizer.module.evidence.entity.EvidenceAnalysis;
import com.winter.airesumeoptimizer.module.export.service.ExportArtifactCleanupService;
import com.winter.airesumeoptimizer.module.job.entity.JobDescription;
import com.winter.airesumeoptimizer.module.job.mapper.JobDescriptionMapper;
import com.winter.airesumeoptimizer.module.job.service.JobDescriptionService;
import com.winter.airesumeoptimizer.module.job.vo.JobDescriptionVO;
import com.winter.airesumeoptimizer.module.optimization.entity.JobTarget;
import com.winter.airesumeoptimizer.module.optimization.entity.OptimizationTask;
import com.winter.airesumeoptimizer.module.optimization.entity.ResumeVersion;
import com.winter.airesumeoptimizer.module.optimization.mapper.JobTargetMapper;
import com.winter.airesumeoptimizer.module.optimization.mapper.OptimizationTaskMapper;
import com.winter.airesumeoptimizer.module.optimization.mapper.ResumeVersionMapper;
import com.winter.airesumeoptimizer.module.optimization.service.OptimizationTaskService;
import com.winter.airesumeoptimizer.module.optimization.vo.OptimizationTaskVO;
import com.winter.airesumeoptimizer.module.resume.entity.Resume;
import com.winter.airesumeoptimizer.module.resume.entity.ResumeParseResult;
import com.winter.airesumeoptimizer.module.resume.mapper.ResumeMapper;
import com.winter.airesumeoptimizer.module.resume.mapper.ResumeParseResultMapper;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OptimizationTaskServiceImplTest {

    private final ResumeMapper resumeMapper = mock(ResumeMapper.class);
    private final ResumeParseResultMapper resumeParseResultMapper = mock(ResumeParseResultMapper.class);
    private final JobDescriptionService jobDescriptionService = mock(JobDescriptionService.class);
    private final JobDescriptionMapper jobDescriptionMapper = mock(JobDescriptionMapper.class);
    private final JobTargetMapper jobTargetMapper = mock(JobTargetMapper.class);
    private final ResumeVersionMapper resumeVersionMapper = mock(ResumeVersionMapper.class);
    private final OptimizationTaskMapper optimizationTaskMapper = mock(OptimizationTaskMapper.class);
    private final AiJobMatchResultMapper aiJobMatchResultMapper = mock(AiJobMatchResultMapper.class);
    private final ExportArtifactCleanupService exportArtifactCleanupService = mock(ExportArtifactCleanupService.class);
    private final OptimizationTaskServiceImpl service = new OptimizationTaskServiceImpl(
            resumeMapper,
            resumeParseResultMapper,
            jobDescriptionService,
            jobDescriptionMapper,
            jobTargetMapper,
            resumeVersionMapper,
            optimizationTaskMapper,
            aiJobMatchResultMapper,
            exportArtifactCleanupService,
            new ObjectMapper());

    private Resume resume;
    private JobDescription jobDescription;

    @BeforeEach
    void setUp() {
        resume = new Resume();
        resume.setId(10L);
        resume.setUserId(1L);
        resume.setOriginalFilename("resume.pdf");
        jobDescription = new JobDescription();
        jobDescription.setId(20L);
        jobDescription.setUserId(1L);
        jobDescription.setTitle("Java 后端");
        jobDescription.setRawText("Java 后端\n负责 Spring Boot 服务开发");

        when(resumeMapper.selectOne(any())).thenReturn(resume);
        ResumeParseResult readyParseResult = new ResumeParseResult();
        readyParseResult.setResumeId(10L);
        readyParseResult.setParseStatus("SUCCESS");
        readyParseResult.setQualityStatus("READY");
        readyParseResult.setCanonicalSourceVersionId(40L);
        readyParseResult.setUnresolvedItems("[]");
        when(resumeParseResultMapper.selectOne(any())).thenReturn(readyParseResult);
        when(resumeVersionMapper.selectOne(any())).thenReturn(sourceVersion());
        when(jobDescriptionService.submit(any(), any())).thenReturn(JobDescriptionVO.builder().id(20L).build());
        when(jobDescriptionMapper.selectOne(any())).thenReturn(jobDescription);
        when(jobTargetMapper.selectOne(any())).thenReturn(null);
        when(jobTargetMapper.insert(any(JobTarget.class))).thenAnswer(invocation -> {
            JobTarget target = invocation.getArgument(0);
            target.setId(30L);
            return 1;
        });
        AtomicLong versionIds = new AtomicLong(41L);
        when(resumeVersionMapper.insert(any(ResumeVersion.class))).thenAnswer(invocation -> {
            ResumeVersion version = invocation.getArgument(0);
            version.setId(versionIds.getAndIncrement());
            return 1;
        });
        when(optimizationTaskMapper.insert(any(OptimizationTask.class))).thenAnswer(invocation -> {
            OptimizationTask task = invocation.getArgument(0);
            task.setId(50L);
            return 1;
        });
    }

    @Test
    void deleteRemovesOnlyTheTaskAndTargetVersionWithoutTouchingTheSourceResume() {
        OptimizationTask stored = task("SUCCESS");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(stored);
        when(optimizationTaskMapper.delete(any())).thenReturn(1);
        when(optimizationTaskMapper.selectCount(any())).thenReturn(0L);
        when(resumeVersionMapper.selectCount(any())).thenReturn(0L);

        service.delete(1L, stored.getId());

        verify(exportArtifactCleanupService).deleteArtifactsForOptimizationTask(1L, stored.getId());
        verify(optimizationTaskMapper).delete(any());
        verify(resumeVersionMapper).delete(any());
        verify(resumeMapper, never()).delete(any());
    }

    @Test
    void listRecentReturnsUserOwnedTaskViewsInMapperOrder() {
        OptimizationTask first = task("SUCCESS");
        first.setId(51L);
        first.setUpdatedAt(java.time.LocalDateTime.of(2026, 1, 2, 10, 0));
        OptimizationTask second = task("RUNNING");
        second.setId(50L);
        second.setUpdatedAt(java.time.LocalDateTime.of(2026, 1, 1, 10, 0));
        when(optimizationTaskMapper.selectList(any())).thenReturn(java.util.List.of(first, second));
        when(optimizationTaskMapper.selectOne(any())).thenReturn(first, second);
        when(jobTargetMapper.selectOne(any())).thenReturn(jobTarget());

        java.util.List<OptimizationTaskVO> result = service.listRecent(1L, 5);

        assertThat(result).extracting(OptimizationTaskVO::getOptimizationTaskId)
                .containsExactly(51L, 50L);
        assertThat(result).extracting(OptimizationTaskVO::getStatus)
                .containsExactly("SUCCESS", "RUNNING");
        verify(optimizationTaskMapper).selectList(any());
    }

    @Test
    void listRecentBoundsRequestedLimitBeforeBuildingQuery() {
        when(optimizationTaskMapper.selectList(any())).thenReturn(java.util.List.of());

        service.listRecent(1L, 0);
        service.listRecent(1L, 99);

        verify(optimizationTaskMapper, org.mockito.Mockito.times(2)).selectList(any());
    }

    @Test
    void createShouldDeriveTargetedVersionWithoutChangingSourceResume() {
        OptimizationTaskVO result = service.create(
                1L,
                10L,
                "Java 后端",
                "Java 后端\n负责 Spring Boot 服务开发",
                "SYSTEM_DEFAULT_OPENAI_COMPATIBLE",
                "test-model");

        assertThat(result.getOptimizationTaskId()).isEqualTo(50L);
        assertThat(result.getSourceResumeVersionId()).isEqualTo(40L);
        assertThat(result.getTargetResumeVersionId()).isEqualTo(41L);
        assertThat(result.getJobTargetId()).isEqualTo(30L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getResumeName()).isEqualTo("resume.pdf");

        ArgumentCaptor<ResumeVersion> versionCaptor = ArgumentCaptor.forClass(ResumeVersion.class);
        verify(resumeVersionMapper, org.mockito.Mockito.times(1)).insert(versionCaptor.capture());
        ResumeVersion targeted = versionCaptor.getValue();
        assertThat(targeted.getVersionType()).isEqualTo("TARGETED");
        assertThat(targeted.getSourceVersionId()).isEqualTo(40L);
        assertThat(targeted.getJobTargetId()).isEqualTo(30L);
        assertThat(targeted.getContentStatus()).isEqualTo("READY");
        assertThat(targeted.getStructuredContent()).isEqualTo(sourceVersion().getStructuredContent());
        assertThat(targeted.getContentRevision()).isZero();
        ArgumentCaptor<OptimizationTask> taskCaptor = ArgumentCaptor.forClass(OptimizationTask.class);
        verify(optimizationTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getResumeInputSnapshot()).isEqualTo(sourceVersion().getStructuredContent());
        verify(resumeVersionMapper, never()).update(any(), any());
        verify(resumeMapper, never()).updateById(any(Resume.class));
    }

    @Test
    void createFromExistingShouldMigrateLegacyInputsWithoutDuplicatingJobDescription() {
        OptimizationTaskVO result = service.createFromExisting(
                1L,
                10L,
                20L,
                "SYSTEM_DEFAULT_OPENAI_COMPATIBLE",
                "test-model");

        assertThat(result.getOptimizationTaskId()).isEqualTo(50L);
        assertThat(result.getJobTargetId()).isEqualTo(30L);
        verify(jobDescriptionService, never()).submit(any(), any());
    }

    @Test
    void createShouldRejectResumeNotOwnedByCurrentUser() {
        when(resumeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(
                2L,
                10L,
                "Java 后端",
                "Java 后端\n负责 Spring Boot 服务开发",
                "SYSTEM_DEFAULT_OPENAI_COMPATIBLE",
                "test-model"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("简历不存在");

        verify(jobTargetMapper, never()).insert(any(JobTarget.class));
        verify(resumeVersionMapper, never()).insert(any(ResumeVersion.class));
        verify(optimizationTaskMapper, never()).insert(any(OptimizationTask.class));
    }

    @Test
    void createShouldRejectReadyRowWithoutCanonicalDocument() {
        ResumeParseResult incomplete = new ResumeParseResult();
        incomplete.setResumeId(10L);
        incomplete.setParseStatus("SUCCESS");
        incomplete.setQualityStatus("READY");
        incomplete.setUnresolvedItems("[]");
        when(resumeParseResultMapper.selectOne(any())).thenReturn(incomplete);

        assertThatThrownBy(() -> service.create(
                1L, 10L, "Java 后端", "Java 后端\n负责 Spring Boot 服务开发",
                "SYSTEM_DEFAULT_OPENAI_COMPATIBLE", "test-model"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(resumeVersionMapper, never()).insert(any(ResumeVersion.class));
    }

    @Test
    void createShouldRejectResumeWithoutConfirmedDeliveryQuality() {
        // Slice A 质量门：解析中 / 待确认 / 失败的简历不得携带进入新分析任务。
        for (String qualityStatus : java.util.List.of("PENDING", "NEEDS_REVIEW", "FAILED")) {
            ResumeParseResult unconfirmed = new ResumeParseResult();
            unconfirmed.setResumeId(10L);
            unconfirmed.setQualityStatus(qualityStatus);
            when(resumeParseResultMapper.selectOne(any())).thenReturn(unconfirmed);

            assertThatThrownBy(() -> service.create(
                    1L,
                    10L,
                    "Java 后端",
                    "Java 后端\n负责 Spring Boot 服务开发",
                    "SYSTEM_DEFAULT_OPENAI_COMPATIBLE",
                    "test-model"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(409));
        }
        verify(resumeVersionMapper, never()).insert(any(ResumeVersion.class));
        verify(optimizationTaskMapper, never()).insert(any(OptimizationTask.class));
    }

    @Test
    void attachAsyncTaskShouldRejectMutationOfCompletedHistory() {
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task("SUCCESS"));

        assertThatThrownBy(() -> service.attachAsyncTask(1L, 50L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已完成的优化任务不能重试");

        verify(optimizationTaskMapper, never()).update(any(), any());
    }

    @Test
    void captureResumeSnapshotShouldOnlyBackfillTargetWhenSourceIsAlreadyFrozen() {
        OptimizationTask task = task("RUNNING");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);
        when(resumeVersionMapper.update(any(), any())).thenReturn(1);
        when(optimizationTaskMapper.update(any(), any())).thenReturn(1);

        service.captureResumeSnapshot(1L, 50L, sourceVersion().getStructuredContent());

        verify(resumeVersionMapper, org.mockito.Mockito.times(1)).update(any(), any());
        verify(optimizationTaskMapper).update(any(), any());
    }

    @Test
    void captureResumeSnapshotShouldRejectLateReparseInsteadOfRewritingSource() {
        OptimizationTask task = task("RUNNING");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);

        assertThatThrownBy(() -> service.captureResumeSnapshot(1L, 50L,
                "{\"schemaVersion\":\"RESUME_DOCUMENT_V1\",\"different\":true}"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));

        verify(resumeVersionMapper, never()).update(any(), any());
        verify(optimizationTaskMapper, never()).update(any(), any());
    }

    @Test
    void captureResumeSnapshotShouldNotReplaceFrozenInputOnRetry() {
        OptimizationTask task = task("RUNNING");
        task.setResumeInputSnapshot("{\"skills\":[\"原始快照\"]}");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);

        service.captureResumeSnapshot(1L, 50L, canonicalSnapshot());

        verify(optimizationTaskMapper, never()).update(any(), any());
        verify(resumeVersionMapper, never()).update(any(), any());
    }

    @Test
    void attachAsyncTaskShouldRejectLostConcurrentClaim() {
        OptimizationTask failed = task("FAILED");
        failed.setAsyncTaskId(90L);
        when(optimizationTaskMapper.selectOne(any())).thenReturn(failed);
        when(optimizationTaskMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.attachAsyncTask(1L, 50L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("岗位分析正在进行中或已完成");

        verify(optimizationTaskMapper).update(any(), any());
    }

    @Test
    void markSuccessShouldRequireSnapshotAndPersistResultConfiguration() {
        OptimizationTask task = task("RUNNING");
        task.setResumeInputSnapshot("{\"skills\":[\"Java\"]}");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);
        when(resumeVersionMapper.selectOne(any())).thenReturn(sourceVersion());
        when(jobTargetMapper.selectOne(any())).thenReturn(jobTarget());
        when(optimizationTaskMapper.update(any(), any())).thenReturn(1);
        when(jobTargetMapper.update(any(), any())).thenReturn(1);

        service.markSuccess(
                1L,
                50L,
                JobDescriptionVO.builder().title("高级 Java 工程师").promptVersion("job-v1").build(),
                evidenceAnalysis(50L, 1L));

        verify(optimizationTaskMapper).update(any(), any());
        verify(jobTargetMapper).update(any(), any());
    }

    @Test
    void markSuccessShouldRejectEvidenceAnalysisFromAnotherTask() {
        OptimizationTask task = task("RUNNING");
        task.setResumeInputSnapshot("{\"skills\":[\"Java\"]}");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);

        assertThatThrownBy(() -> service.markSuccess(1L, 50L, null, evidenceAnalysis(999L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("岗位证据分析不属于当前优化任务");

        verify(optimizationTaskMapper, never()).update(any(), any());
    }

    @Test
    void markSuccessShouldRejectEvidenceAnalysisOwnedByAnotherUser() {
        OptimizationTask task = task("RUNNING");
        task.setResumeInputSnapshot("{\"skills\":[\"Java\"]}");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);

        assertThatThrownBy(() -> service.markSuccess(1L, 50L, null, evidenceAnalysis(50L, 2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("岗位证据分析不属于当前优化任务");

        verify(optimizationTaskMapper, never()).update(any(), any());
    }

    @Test
    void markSuccessShouldFailWhenInputSnapshotWasNotCaptured() {
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task("RUNNING"));

        assertThatThrownBy(() -> service.markSuccess(1L, 50L, null, evidenceAnalysis(50L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("优化任务缺少简历输入快照");

        verify(optimizationTaskMapper, never()).update(any(), any());
    }

    @Test
    void createShouldFreezeByokSelectionSnapshotWithoutSecrets() {
        AiSelectionSnapshot byok = byokSelection(5L);

        service.create(
                1L,
                10L,
                "Java 后端",
                "Java 后端\n负责 Spring Boot 服务开发",
                byok);

        ArgumentCaptor<OptimizationTask> taskCaptor = ArgumentCaptor.forClass(OptimizationTask.class);
        verify(optimizationTaskMapper).insert(taskCaptor.capture());
        OptimizationTask persisted = taskCaptor.getValue();
        assertThat(persisted.getAiSourceSnapshot()).isEqualTo("USER_BYOK");
        assertThat(persisted.getAiProviderSnapshot()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(persisted.getProviderSnapshot()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(persisted.getAiCredentialId()).isEqualTo(77L);
        assertThat(persisted.getAiCredentialIdSnapshot()).isEqualTo(77L);
        assertThat(persisted.getAiCredentialRevision()).isEqualTo(5L);
        assertThat(persisted.getAiBaseUrlSnapshot()).isEqualTo("https://byok.example.com:443/v1");
        assertThat(persisted.getModelSnapshot()).isEqualTo("byok-model");
        assertThat(persisted.getAiConfigSnapshot()).isEqualTo("{\"temperature\":0.2,\"maxOutputTokens\":16000}");
    }

    @Test
    void createShouldRejectIncompleteByokSelection() {
        AiSelectionSnapshot incomplete = new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                77L,
                null,
                "https://byok.example.com:443/v1",
                "byok-model",
                "{\"temperature\":0.2,\"maxOutputTokens\":16000}",
                null);

        assertThatThrownBy(() -> service.create(
                1L,
                10L,
                "Java 后端",
                "Java 后端\n负责 Spring Boot 服务开发",
                incomplete))
                .isInstanceOf(BusinessException.class)
                .hasMessage("优化任务的 BYOK 选择快照不完整");

        verify(optimizationTaskMapper, never()).insert(any(OptimizationTask.class));
    }

    @Test
    void executionContextShouldReuseFrozenByokSnapshotEvenAfterCredentialRemoval() {
        OptimizationTask stored = task("FAILED");
        stored.setAiSourceSnapshot("USER_BYOK");
        stored.setAiProviderSnapshot("OPENAI_COMPATIBLE");
        // The live FK is gone after deletion; the immutable snapshot must survive.
        stored.setAiCredentialId(null);
        stored.setAiCredentialIdSnapshot(77L);
        stored.setAiCredentialRevision(5L);
        stored.setAiBaseUrlSnapshot("https://byok.example.com:443/v1");
        stored.setModelSnapshot("byok-model");
        stored.setAiConfigSnapshot("{\"temperature\":0.2,\"maxOutputTokens\":16000}");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(stored);
        when(resumeVersionMapper.selectOne(any())).thenReturn(sourceVersion());
        when(jobTargetMapper.selectOne(any())).thenReturn(jobTarget());

        OptimizationTaskService.ExecutionContext context = service.getExecutionContext(1L, 50L);

        assertThat(context.aiSelection()).isNotNull();
        assertThat(context.aiSelection().source()).isEqualTo(AiSource.USER_BYOK);
        assertThat(context.aiSelection().credentialId()).isEqualTo(77L);
        assertThat(context.aiSelection().credentialRevision()).isEqualTo(5L);
        assertThat(context.aiSelection().baseUrl()).isEqualTo("https://byok.example.com:443/v1");
        assertThat(context.aiSelection().model()).isEqualTo("byok-model");
    }

    @Test
    void executionContextShouldKeepLegacyTasksOnSystemDefault() {
        OptimizationTask legacyTask = task("SUCCESS");
        legacyTask.setProviderSnapshot("SYSTEM_DEFAULT_OPENAI_COMPATIBLE");
        legacyTask.setModelSnapshot("test-model");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(legacyTask);
        when(resumeVersionMapper.selectOne(any())).thenReturn(sourceVersion());
        when(jobTargetMapper.selectOne(any())).thenReturn(jobTarget());

        OptimizationTaskService.ExecutionContext context = service.getExecutionContext(1L, 50L);

        assertThat(context.aiSelection()).isNotNull();
        assertThat(context.aiSelection().source()).isEqualTo(AiSource.SYSTEM_DEFAULT);
        assertThat(context.aiSelection().credentialId()).isNull();
        assertThat(context.aiSelection().credentialRevision()).isNull();
    }

    @Test
    void executionContextShouldRejectCorruptedByokSnapshot() {
        OptimizationTask corrupted = task("FAILED");
        corrupted.setAiSourceSnapshot("USER_BYOK");
        corrupted.setAiProviderSnapshot("OPENAI_COMPATIBLE");
        corrupted.setAiCredentialIdSnapshot(77L);
        corrupted.setAiCredentialRevision(null);
        corrupted.setAiBaseUrlSnapshot("https://byok.example.com:443/v1");
        corrupted.setModelSnapshot("byok-model");
        corrupted.setAiConfigSnapshot("{\"temperature\":0.2,\"maxOutputTokens\":16000}");
        when(optimizationTaskMapper.selectOne(any())).thenReturn(corrupted);
        when(resumeVersionMapper.selectOne(any())).thenReturn(sourceVersion());
        when(jobTargetMapper.selectOne(any())).thenReturn(jobTarget());

        assertThatThrownBy(() -> service.getExecutionContext(1L, 50L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("优化任务的 BYOK 选择快照损坏");
    }

    private AiSelectionSnapshot byokSelection(Long revision) {
        return new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                77L,
                revision,
                "https://byok.example.com:443/v1",
                "byok-model",
                "{\"temperature\":0.2,\"maxOutputTokens\":16000}",
                null);
    }

    private EvidenceAnalysis evidenceAnalysis(Long optimizationTaskId, Long userId) {
        EvidenceAnalysis analysis = new EvidenceAnalysis();
        analysis.setId(60L);
        analysis.setUserId(userId);
        analysis.setOptimizationTaskId(optimizationTaskId);
        analysis.setMatchedCount(1);
        analysis.setPartialEvidenceCount(1);
        analysis.setNoEvidenceCount(1);
        analysis.setModelName("test-model");
        analysis.setPromptVersion("evidence_match_v1");
        return analysis;
    }

    private String canonicalSnapshot() {
        return "{\"schemaVersion\":\"RESUME_DOCUMENT_V1\",\"basics\":{\"name\":\"张三\",\"contacts\":[]},\"sections\":[]}";
    }

    private ResumeVersion sourceVersion() {
        ResumeVersion version = new ResumeVersion();
        version.setId(40L);
        version.setUserId(1L);
        version.setResumeId(10L);
        version.setVersionType("SOURCE");
        version.setSourceType("PARSED_UPLOAD");
        version.setContentStatus("READY");
        version.setStructuredContent(
                "{\"schemaVersion\":\"RESUME_DOCUMENT_V1\",\"basics\":{\"name\":\"张三\",\"contacts\":[]},\"sections\":[]}");
        version.setContentRevision(0L);
        return version;
    }

    private JobTarget jobTarget() {
        JobTarget target = new JobTarget();
        target.setId(30L);
        target.setUserId(1L);
        target.setLegacyJobDescriptionId(20L);
        return target;
    }

    private OptimizationTask task(String status) {
        OptimizationTask task = new OptimizationTask();
        task.setId(50L);
        task.setUserId(1L);
        task.setSourceResumeVersionId(40L);
        task.setTargetResumeVersionId(41L);
        task.setJobTargetId(30L);
        task.setStatus(status);
        return task;
    }
}
