package com.winter.airesumeoptimizer.module.optimization.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.common.logging.LogSanitizer;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.module.analysis.entity.AiJobMatchResult;
import com.winter.airesumeoptimizer.module.analysis.mapper.AiJobMatchResultMapper;
import com.winter.airesumeoptimizer.module.evidence.entity.EvidenceAnalysis;
import com.winter.airesumeoptimizer.module.export.service.ExportArtifactCleanupService;
import com.winter.airesumeoptimizer.module.job.dto.JobDescriptionSubmitDTO;
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
import com.winter.airesumeoptimizer.module.resume.enums.ResumeQualityStatus;
import com.winter.airesumeoptimizer.module.resume.mapper.ResumeMapper;
import com.winter.airesumeoptimizer.module.resume.mapper.ResumeParseResultMapper;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OptimizationTaskServiceImpl implements OptimizationTaskService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String CONTENT_PENDING = "PENDING";
    private static final String CONTENT_READY = "READY";
    private static final String VERSION_SOURCE = "SOURCE";
    private static final String VERSION_TARGETED = "TARGETED";
    private static final String SOURCE_JOB_DERIVATION = "JOB_DERIVATION";
    private static final String SOURCE_USER_INPUT = "USER_INPUT";
    private static final String DEFAULT_RULES_SNAPSHOT = "{}";
    private static final String DEFAULT_PROMPT_SNAPSHOT = "{}";
    private static final String DEFAULT_TEMPLATE_VERSION = "NOT_SELECTED";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final ResumeMapper resumeMapper;
    private final ResumeParseResultMapper resumeParseResultMapper;
    private final JobDescriptionService jobDescriptionService;
    private final JobDescriptionMapper jobDescriptionMapper;
    private final JobTargetMapper jobTargetMapper;
    private final ResumeVersionMapper resumeVersionMapper;
    private final OptimizationTaskMapper optimizationTaskMapper;
    private final AiJobMatchResultMapper aiJobMatchResultMapper;
    private final ExportArtifactCleanupService exportArtifactCleanupService;
    private final ObjectMapper objectMapper;

    public OptimizationTaskServiceImpl(
            ResumeMapper resumeMapper,
            ResumeParseResultMapper resumeParseResultMapper,
            JobDescriptionService jobDescriptionService,
            JobDescriptionMapper jobDescriptionMapper,
            JobTargetMapper jobTargetMapper,
            ResumeVersionMapper resumeVersionMapper,
            OptimizationTaskMapper optimizationTaskMapper,
            AiJobMatchResultMapper aiJobMatchResultMapper,
            ExportArtifactCleanupService exportArtifactCleanupService,
            ObjectMapper objectMapper) {
        this.resumeMapper = resumeMapper;
        this.resumeParseResultMapper = resumeParseResultMapper;
        this.jobDescriptionService = jobDescriptionService;
        this.jobDescriptionMapper = jobDescriptionMapper;
        this.jobTargetMapper = jobTargetMapper;
        this.resumeVersionMapper = resumeVersionMapper;
        this.optimizationTaskMapper = optimizationTaskMapper;
        this.aiJobMatchResultMapper = aiJobMatchResultMapper;
        this.exportArtifactCleanupService = exportArtifactCleanupService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public OptimizationTaskVO create(
            Long userId,
            Long resumeId,
            String jobTitle,
            String rawJobDescription,
            String providerSnapshot,
            String modelSnapshot) {
        return create(userId, resumeId, jobTitle, rawJobDescription, legacySystemSelection(providerSnapshot, modelSnapshot));
    }

    @Override
    @Transactional
    public OptimizationTaskVO create(
            Long userId,
            Long resumeId,
            String jobTitle,
            String rawJobDescription,
            AiSelectionSnapshot selection) {
        getOwnedResume(userId, resumeId);
        // 在保存兼容 JobDescription 之前先过简历交付质量门，避免 NEEDS_REVIEW 产生孤立 JD 行。
        requireConfirmedParseResult(userId, resumeId);
        JobDescriptionSubmitDTO submitRequest = new JobDescriptionSubmitDTO();
        submitRequest.setTitle(jobTitle);
        submitRequest.setRawText(rawJobDescription);
        JobDescriptionVO submittedJob = jobDescriptionService.submit(userId, submitRequest);
        return createFromLegacyJob(
                userId,
                resumeId,
                getOwnedJobDescription(userId, submittedJob.getId()),
                requireSelection(selection));
    }

    @Override
    @Transactional
    public OptimizationTaskVO createFromExisting(
            Long userId,
            Long resumeId,
            Long jobDescriptionId,
            String providerSnapshot,
            String modelSnapshot) {
        return createFromExisting(
                userId,
                resumeId,
                jobDescriptionId,
                legacySystemSelection(providerSnapshot, modelSnapshot));
    }

    @Override
    @Transactional
    public OptimizationTaskVO createFromExisting(
            Long userId,
            Long resumeId,
            Long jobDescriptionId,
            AiSelectionSnapshot selection) {
        return createFromLegacyJob(
                userId,
                resumeId,
                getOwnedJobDescription(userId, jobDescriptionId),
                requireSelection(selection));
    }

    @Override
    public OptimizationTaskVO get(Long userId, Long optimizationTaskId) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        ResumeVersion sourceVersion = getOwnedVersion(userId, task.getSourceResumeVersionId());
        JobTarget jobTarget = getOwnedJobTarget(userId, task.getJobTargetId());
        Resume resume = getOwnedResume(userId, sourceVersion.getResumeId());
        return toVO(task, sourceVersion, jobTarget, resume);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long optimizationTaskId) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        Long targetVersionId = task.getTargetResumeVersionId();
        Long jobTargetId = task.getJobTargetId();

        // ExportArtifact 的数据库级联不会删除对象存储文件，必须先走正式清理 seam。
        exportArtifactCleanupService.deleteArtifactsForOptimizationTask(userId, task.getId());

        int taskRows = optimizationTaskMapper.delete(new LambdaQueryWrapper<OptimizationTask>()
                .eq(OptimizationTask::getId, task.getId())
                .eq(OptimizationTask::getUserId, userId));
        if (taskRows != 1) {
            throw new BusinessException(404, "优化任务不存在");
        }

        // TARGET 只属于这条任务；删除它不会触碰 SOURCE / 原始简历。
        if (targetVersionId != null) {
            resumeVersionMapper.delete(new LambdaQueryWrapper<ResumeVersion>()
                    .eq(ResumeVersion::getId, targetVersionId)
                    .eq(ResumeVersion::getUserId, userId)
                    .eq(ResumeVersion::getVersionType, VERSION_TARGETED));
        }

        // JobTarget 可能被同一岗位的其它任务复用，只在确认没有其它派生数据时清理它。
        if (jobTargetId != null) {
            long remainingTasks = optimizationTaskMapper.selectCount(new LambdaQueryWrapper<OptimizationTask>()
                    .eq(OptimizationTask::getUserId, userId)
                    .eq(OptimizationTask::getJobTargetId, jobTargetId));
            long remainingVersions = resumeVersionMapper.selectCount(new LambdaQueryWrapper<ResumeVersion>()
                    .eq(ResumeVersion::getUserId, userId)
                    .eq(ResumeVersion::getJobTargetId, jobTargetId));
            if (remainingTasks == 0 && remainingVersions == 0) {
                jobTargetMapper.delete(new LambdaQueryWrapper<JobTarget>()
                        .eq(JobTarget::getId, jobTargetId)
                        .eq(JobTarget::getUserId, userId));
            }
        }
    }

    @Override
    public java.util.List<OptimizationTaskVO> listRecent(Long userId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 20));
        return optimizationTaskMapper.selectList(new LambdaQueryWrapper<OptimizationTask>()
                        .eq(OptimizationTask::getUserId, userId)
                        .orderByDesc(OptimizationTask::getUpdatedAt)
                        .orderByDesc(OptimizationTask::getId)
                        .last("LIMIT " + bounded))
                .stream().map(task -> get(userId, task.getId())).toList();
    }

    @Override
    public String getFrozenSourceCanonicalDocument(Long userId, Long optimizationTaskId) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        ResumeVersion sourceVersion = getOwnedVersion(userId, task.getSourceResumeVersionId());
        if (!VERSION_SOURCE.equals(sourceVersion.getVersionType())
                || sourceVersion.getSourceVersionId() != null
                || sourceVersion.getJobTargetId() != null
                || !CONTENT_READY.equals(sourceVersion.getContentStatus())
                || isBlank(sourceVersion.getStructuredContent())) {
            return null;
        }
        return sourceVersion.getStructuredContent();
    }

    @Override
    public OptimizationTaskVO findByLegacyInputs(Long userId, Long resumeId, Long jobDescriptionId) {
        getOwnedResume(userId, resumeId);
        getOwnedJobDescription(userId, jobDescriptionId);
        JobTarget jobTarget = jobTargetMapper.selectOne(new LambdaQueryWrapper<JobTarget>()
                .eq(JobTarget::getUserId, userId)
                .eq(JobTarget::getLegacyJobDescriptionId, jobDescriptionId));
        if (jobTarget == null) {
            throw new BusinessException(404, "优化任务不存在");
        }

        List<ResumeVersion> targetVersions = resumeVersionMapper.selectList(new LambdaQueryWrapper<ResumeVersion>()
                .eq(ResumeVersion::getUserId, userId)
                .eq(ResumeVersion::getResumeId, resumeId)
                .eq(ResumeVersion::getJobTargetId, jobTarget.getId())
                .eq(ResumeVersion::getVersionType, VERSION_TARGETED));
        if (targetVersions.isEmpty()) {
            throw new BusinessException(404, "优化任务不存在");
        }

        OptimizationTask task = optimizationTaskMapper.selectOne(new LambdaQueryWrapper<OptimizationTask>()
                .eq(OptimizationTask::getUserId, userId)
                .in(OptimizationTask::getTargetResumeVersionId, targetVersions.stream().map(ResumeVersion::getId).toList())
                .orderByDesc(OptimizationTask::getCreatedAt)
                .last("LIMIT 1"));
        if (task == null) {
            throw new BusinessException(404, "优化任务不存在");
        }
        return get(userId, task.getId());
    }

    @Override
    public ExecutionContext getExecutionContext(Long userId, Long optimizationTaskId) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        ResumeVersion sourceVersion = getOwnedVersion(userId, task.getSourceResumeVersionId());
        ResumeVersion targetVersion = getOwnedVersion(userId, task.getTargetResumeVersionId());
        JobTarget jobTarget = getOwnedJobTarget(userId, task.getJobTargetId());
        if (!sourceVersion.getResumeId().equals(targetVersion.getResumeId())) {
            throw new BusinessException(500, "优化任务的简历版本关系不一致");
        }
        return new ExecutionContext(
                task.getId(),
                sourceVersion.getResumeId(),
                jobTarget.getLegacyJobDescriptionId(),
                jobTarget.getId(),
                sourceVersion.getId(),
                targetVersion.getId(),
                toAiSelection(task),
                task.getResumeInputSnapshot());
    }

    @Override
    @Transactional
    public void attachAsyncTask(Long userId, Long optimizationTaskId, Long asyncTaskId) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        if (STATUS_SUCCESS.equals(task.getStatus())) {
            throw new BusinessException(409, "已完成的优化任务不能重试");
        }
        if (task.getAsyncTaskId() != null
                && (STATUS_PENDING.equals(task.getStatus()) || STATUS_RUNNING.equals(task.getStatus()))) {
            throw new BusinessException(409, "岗位分析正在进行中");
        }
        if (asyncTaskId == null) {
            throw new BusinessException(400, "后台任务 ID 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = optimizationTaskMapper.update(null, new UpdateWrapper<OptimizationTask>()
                .eq("id", optimizationTaskId)
                .eq("user_id", userId)
                .ne("status", STATUS_SUCCESS)
                .and(wrapper -> wrapper.isNull("async_task_id")
                        .or()
                        .in("status", STATUS_FAILED, "CANCELLED"))
                .set("async_task_id", asyncTaskId)
                .set("status", STATUS_PENDING)
                .set("error_code", null)
                .set("error_message", null)
                .set("finished_at", null)
                .set("updated_at", now));
        if (rows != 1) {
            OptimizationTask current = getOwnedTask(userId, optimizationTaskId);
            if (STATUS_SUCCESS.equals(current.getStatus())) {
                throw new BusinessException(409, "已完成的优化任务不能重试");
            }
            throw new BusinessException(409, "岗位分析正在进行中或已完成");
        }
    }

    @Override
    @Transactional
    public void captureResumeSnapshot(Long userId, Long optimizationTaskId, String structuredContent) {
        if (structuredContent == null || structuredContent.isBlank()
                || !isCanonicalDocument(structuredContent)) {
            throw new BusinessException(400, "简历 canonical 内容格式不正确");
        }
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        if (task.getResumeInputSnapshot() != null && !task.getResumeInputSnapshot().isBlank()) {
            return;
        }
        ResumeVersion source = getOwnedVersion(userId, task.getSourceResumeVersionId());
        String frozenContent = source.getStructuredContent();
        boolean sourceAlreadyFrozen = frozenContent != null && !frozenContent.isBlank();
        if (sourceAlreadyFrozen) {
            // SOURCE is shared historical truth. A late parse result must never replace it;
            // a mismatch means the task lost its original freeze boundary and must fail closed.
            if (!frozenContent.equals(structuredContent)) {
                throw new BusinessException(409, "优化任务的简历 SOURCE 已变化，不能覆盖冻结快照");
            }
            structuredContent = frozenContent;
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = optimizationTaskMapper.update(null, new UpdateWrapper<OptimizationTask>()
                .eq("id", optimizationTaskId)
                .eq("user_id", userId)
                .isNull("resume_input_snapshot")
                .set("resume_input_snapshot", structuredContent)
                .set("updated_at", now));
        if (claimed != 1) {
            OptimizationTask current = getOwnedTask(userId, optimizationTaskId);
            if (current.getResumeInputSnapshot() != null && !current.getResumeInputSnapshot().isBlank()) {
                return;
            }
            throw new BusinessException(409, "简历输入快照正在保存，请稍后重试");
        }
        if (!sourceAlreadyFrozen) {
            populatePendingSourceVersion(userId, source.getId(), structuredContent, now);
        }
        updateOwnedVersion(userId, task.getTargetResumeVersionId(), structuredContent, now);
    }

    @Override
    @Transactional
    public void markRunning(Long userId, Long optimizationTaskId) {
        getOwnedTask(userId, optimizationTaskId);
        LocalDateTime now = LocalDateTime.now();
        int rows = optimizationTaskMapper.update(null, new UpdateWrapper<OptimizationTask>()
                .eq("id", optimizationTaskId)
                .eq("user_id", userId)
                .eq("status", STATUS_PENDING)
                .isNotNull("async_task_id")
                .set("status", STATUS_RUNNING)
                .set("started_at", now)
                .set("updated_at", now));
        if (rows != 1) {
            throw new BusinessException(409, "优化任务当前状态不允许开始分析");
        }
    }

    @Override
    @Transactional
    public void markSuccess(
            Long userId,
            Long optimizationTaskId,
            JobDescriptionVO parsedJob,
            EvidenceAnalysis evidenceAnalysis) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        if (evidenceAnalysis == null || evidenceAnalysis.getId() == null) {
            throw new BusinessException(400, "岗位证据分析未成功，不能完成优化任务");
        }
        if (!task.getId().equals(evidenceAnalysis.getOptimizationTaskId())
                || !userId.equals(evidenceAnalysis.getUserId())) {
            throw new BusinessException(400, "岗位证据分析不属于当前优化任务");
        }
        if (task.getResumeInputSnapshot() == null || task.getResumeInputSnapshot().isBlank()) {
            throw new BusinessException(500, "优化任务缺少简历输入快照");
        }

        LocalDateTime now = LocalDateTime.now();
        String promptSnapshot = serializePromptSnapshot(parsedJob, evidenceAnalysis);
        int rows = optimizationTaskMapper.update(null, new UpdateWrapper<OptimizationTask>()
                .eq("id", optimizationTaskId)
                .eq("user_id", userId)
                .eq("status", STATUS_RUNNING)
                .set("status", STATUS_SUCCESS)
                .set("prompt_snapshot", promptSnapshot)
                .set("error_code", null)
                .set("error_message", null)
                .set("finished_at", now)
                .set("updated_at", now));
        if (rows != 1) {
            throw new BusinessException(409, "优化任务当前状态不允许完成分析");
        }

        if (parsedJob != null && parsedJob.getTitle() != null && !parsedJob.getTitle().isBlank()) {
            updateOwnedJobTargetTitle(userId, task.getJobTargetId(), parsedJob.getTitle(), now);
        }
    }

    @Override
    @Transactional
    public void markFailed(Long userId, Long optimizationTaskId, String errorCode, String errorMessage) {
        getOwnedTask(userId, optimizationTaskId);
        LocalDateTime now = LocalDateTime.now();
        int rows = optimizationTaskMapper.update(null, new UpdateWrapper<OptimizationTask>()
                .eq("id", optimizationTaskId)
                .eq("user_id", userId)
                .ne("status", STATUS_SUCCESS)
                .set("status", STATUS_FAILED)
                .set("error_code", truncate(errorCode, 100))
                .set("error_message", truncate(LogSanitizer.sanitize(errorMessage), ERROR_MESSAGE_MAX_LENGTH))
                .set("finished_at", now)
                .set("updated_at", now));
        if (rows == 0) {
            OptimizationTask current = getOwnedTask(userId, optimizationTaskId);
            if (!STATUS_SUCCESS.equals(current.getStatus())) {
                throw new BusinessException(409, "优化任务当前状态不允许标记失败");
            }
        }
    }

    @Override
    public AiJobMatchResult getLegacyAnalysisResult(Long userId, Long optimizationTaskId) {
        OptimizationTask task = getOwnedTask(userId, optimizationTaskId);
        if (task.getAnalysisResultId() == null) {
            throw new BusinessException(404, "岗位分析结果尚未生成");
        }
        AiJobMatchResult result = aiJobMatchResultMapper.selectById(task.getAnalysisResultId());
        if (result == null) {
            throw new BusinessException(404, "岗位分析结果不存在");
        }
        ExecutionContext context = getExecutionContext(userId, optimizationTaskId);
        if (!context.resumeId().equals(result.getResumeId())
                || !context.jobDescriptionId().equals(result.getJobDescriptionId())) {
            throw new BusinessException(500, "优化任务与岗位分析结果不一致");
        }
        return result;
    }

    private OptimizationTaskVO createFromLegacyJob(
            Long userId,
            Long resumeId,
            JobDescription legacyJob,
            AiSelectionSnapshot selection) {
        Resume resume = getOwnedResume(userId, resumeId);
        ResumeVersion sourceVersion = requireConfirmedParseResult(userId, resume.getId());
        if (legacyJob.getRawText() == null || legacyJob.getRawText().isBlank()) {
            throw new BusinessException(400, "目标岗位 JD 原文不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        JobTarget jobTarget = findOrCreateJobTarget(userId, legacyJob, now);
        ResumeVersion targetVersion = createTargetVersion(
                userId,
                resume.getId(),
                sourceVersion,
                jobTarget.getId(),
                now);

        OptimizationTask task = new OptimizationTask();
        task.setUserId(userId);
        task.setSourceResumeVersionId(sourceVersion.getId());
        task.setTargetResumeVersionId(targetVersion.getId());
        task.setJobTargetId(jobTarget.getId());
        task.setStatus(STATUS_PENDING);
        // The confirmed SOURCE is already the task's immutable input; freeze its exact bytes
        // before async dispatch so a later reparse cannot change this task's evidence basis.
        task.setResumeInputSnapshot(sourceVersion.getStructuredContent());
        task.setJobInputSnapshot(legacyJob.getRawText());
        task.setPromptSnapshot(DEFAULT_PROMPT_SNAPSHOT);
        task.setRulesSnapshot(DEFAULT_RULES_SNAPSHOT);
        applyAiSelection(task, selection);
        task.setTemplateVersion(DEFAULT_TEMPLATE_VERSION);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        insertTask(task);
        return toVO(task, sourceVersion, jobTarget, resume);
    }

    /**
     * Slice A 质量门：未确认的解析结果不得携带进入分析链。
     * NEEDS_REVIEW / FAILED / 尚未完成解析都直接拒绝创建，不做静默降级。
     */
    private ResumeVersion requireConfirmedParseResult(Long userId, Long resumeId) {
        ResumeParseResult parseResult = resumeParseResultMapper.selectOne(
                new LambdaQueryWrapper<ResumeParseResult>()
                        .eq(ResumeParseResult::getResumeId, resumeId));
        String qualityStatus = parseResult == null ? null : parseResult.getQualityStatus();
        if (parseResult == null
                || !STATUS_SUCCESS.equals(parseResult.getParseStatus())
                || qualityStatus == null
                || ResumeQualityStatus.QUALITY_PENDING.equals(qualityStatus)) {
            throw new BusinessException(409, "简历尚未解析完成，请先完成解析");
        }
        if (ResumeQualityStatus.QUALITY_NEEDS_REVIEW.equals(qualityStatus)) {
            throw new BusinessException(409, "RESUME_NEEDS_REVIEW：简历内容存在待确认项，确认后才能开始分析");
        }
        if (ResumeQualityStatus.QUALITY_FAILED.equals(qualityStatus)) {
            throw new BusinessException(409, "简历解析失败，请重新解析后再开始分析");
        }
        if (!ResumeQualityStatus.QUALITY_READY.equals(qualityStatus)
                || !hasNoUnresolvedItems(parseResult.getUnresolvedItems())
                || parseResult.getCanonicalSourceVersionId() == null) {
            // 存量解析行没有 canonical SOURCE 时必须重解析，不能把候选 structured_json 当正式事实。
            throw new BusinessException(409, "简历交付内容尚未确认，请重新解析后再开始分析");
        }
        ResumeVersion source = getOwnedVersion(userId, parseResult.getCanonicalSourceVersionId());
        if (!VERSION_SOURCE.equals(source.getVersionType())
                || source.getSourceVersionId() != null
                || source.getJobTargetId() != null
                || !CONTENT_READY.equals(source.getContentStatus())
                || !isCanonicalDocument(source.getStructuredContent())) {
            throw new BusinessException(409, "简历交付内容尚未确认，请重新解析后再开始分析");
        }
        return source;
    }

    private boolean isCanonicalDocument(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            return root != null
                    && root.isObject()
                    && ResumeDocumentDTO.SCHEMA_VERSION.equals(root.path("schemaVersion").asText());
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private boolean hasNoUnresolvedItems(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            return root != null && root.isArray() && root.isEmpty();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private JobTarget findOrCreateJobTarget(Long userId, JobDescription legacyJob, LocalDateTime now) {
        JobTarget existing = jobTargetMapper.selectOne(new LambdaQueryWrapper<JobTarget>()
                .eq(JobTarget::getLegacyJobDescriptionId, legacyJob.getId()));
        if (existing != null) {
            if (!userId.equals(existing.getUserId())) {
                throw new BusinessException(404, "目标岗位不存在");
            }
            return existing;
        }

        JobTarget jobTarget = new JobTarget();
        jobTarget.setUserId(userId);
        jobTarget.setLegacyJobDescriptionId(legacyJob.getId());
        jobTarget.setTitle(legacyJob.getTitle());
        jobTarget.setRawJd(legacyJob.getRawText());
        jobTarget.setSourceType(SOURCE_USER_INPUT);
        jobTarget.setCreatedAt(now);
        jobTarget.setUpdatedAt(now);
        int rows = jobTargetMapper.insert(jobTarget);
        if (rows != 1 || jobTarget.getId() == null) {
            throw new BusinessException(500, "目标岗位保存失败");
        }
        return jobTarget;
    }

    private ResumeVersion createTargetVersion(
            Long userId,
            Long resumeId,
            ResumeVersion sourceVersion,
            Long jobTargetId,
            LocalDateTime now) {
        ResumeVersion version = new ResumeVersion();
        version.setUserId(userId);
        version.setResumeId(resumeId);
        version.setSourceVersionId(sourceVersion.getId());
        version.setJobTargetId(jobTargetId);
        version.setVersionType(VERSION_TARGETED);
        version.setSourceType(SOURCE_JOB_DERIVATION);
        version.setContentStatus(CONTENT_READY);
        version.setStructuredContent(sourceVersion.getStructuredContent());
        version.setContentRevision(0L);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        insertVersion(version);
        return version;
    }

    private void insertVersion(ResumeVersion version) {
        int rows = resumeVersionMapper.insert(version);
        if (rows != 1 || version.getId() == null) {
            throw new BusinessException(500, "简历版本保存失败");
        }
    }

    private void insertTask(OptimizationTask task) {
        int rows = optimizationTaskMapper.insert(task);
        if (rows != 1 || task.getId() == null) {
            throw new BusinessException(500, "优化任务保存失败");
        }
    }

    private Resume getOwnedResume(Long userId, Long resumeId) {
        validateUserId(userId);
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(400, "简历 ID 必须大于 0");
        }
        Resume resume = resumeMapper.selectOne(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getId, resumeId)
                .eq(Resume::getUserId, userId));
        if (resume == null) {
            throw new BusinessException(404, "简历不存在");
        }
        return resume;
    }

    private JobDescription getOwnedJobDescription(Long userId, Long jobDescriptionId) {
        validateUserId(userId);
        if (jobDescriptionId == null || jobDescriptionId <= 0) {
            throw new BusinessException(400, "目标岗位 ID 必须大于 0");
        }
        JobDescription jobDescription = jobDescriptionMapper.selectOne(new LambdaQueryWrapper<JobDescription>()
                .eq(JobDescription::getId, jobDescriptionId)
                .eq(JobDescription::getUserId, userId));
        if (jobDescription == null) {
            throw new BusinessException(404, "目标岗位不存在");
        }
        return jobDescription;
    }

    private OptimizationTask getOwnedTask(Long userId, Long optimizationTaskId) {
        validateUserId(userId);
        if (optimizationTaskId == null || optimizationTaskId <= 0) {
            throw new BusinessException(400, "优化任务 ID 必须大于 0");
        }
        OptimizationTask task = optimizationTaskMapper.selectOne(new LambdaQueryWrapper<OptimizationTask>()
                .eq(OptimizationTask::getId, optimizationTaskId)
                .eq(OptimizationTask::getUserId, userId));
        if (task == null) {
            throw new BusinessException(404, "优化任务不存在");
        }
        return task;
    }

    private ResumeVersion getOwnedVersion(Long userId, Long versionId) {
        ResumeVersion version = resumeVersionMapper.selectOne(new LambdaQueryWrapper<ResumeVersion>()
                .eq(ResumeVersion::getId, versionId)
                .eq(ResumeVersion::getUserId, userId));
        if (version == null) {
            throw new BusinessException(404, "简历版本不存在");
        }
        return version;
    }

    private JobTarget getOwnedJobTarget(Long userId, Long jobTargetId) {
        JobTarget target = jobTargetMapper.selectOne(new LambdaQueryWrapper<JobTarget>()
                .eq(JobTarget::getId, jobTargetId)
                .eq(JobTarget::getUserId, userId));
        if (target == null) {
            throw new BusinessException(404, "目标岗位不存在");
        }
        return target;
    }

    private void updateOwnedTask(Long userId, Long taskId, UpdateWrapper<OptimizationTask> update) {
        update.eq("id", taskId).eq("user_id", userId);
        if (optimizationTaskMapper.update(null, update) != 1) {
            throw new BusinessException(404, "优化任务不存在");
        }
    }

    private void updateOwnedVersion(Long userId, Long versionId, String structuredContent, LocalDateTime now) {
        int rows = resumeVersionMapper.update(null, new UpdateWrapper<ResumeVersion>()
                .eq("id", versionId)
                .eq("user_id", userId)
                .set("content_status", CONTENT_READY)
                .set("structured_content", structuredContent)
                .set("updated_at", now));
        if (rows != 1) {
            throw new BusinessException(404, "简历版本不存在");
        }
    }

    /** 仅兼容旧的、尚未捕获内容的任务；已冻结 SOURCE 永远走只读路径。 */
    private void populatePendingSourceVersion(Long userId, Long versionId, String structuredContent, LocalDateTime now) {
        int rows = resumeVersionMapper.update(null, new UpdateWrapper<ResumeVersion>()
                .eq("id", versionId)
                .eq("user_id", userId)
                .eq("version_type", VERSION_SOURCE)
                .eq("content_status", CONTENT_PENDING)
                .isNull("structured_content")
                .set("content_status", CONTENT_READY)
                .set("structured_content", structuredContent)
                .set("content_revision", 0L)
                .set("updated_at", now));
        if (rows != 1) {
            ResumeVersion current = getOwnedVersion(userId, versionId);
            if (current.getStructuredContent() == null || current.getStructuredContent().isBlank()) {
                throw new BusinessException(409, "简历 SOURCE 正在保存，请稍后重试");
            }
            if (!structuredContent.equals(current.getStructuredContent())) {
                throw new BusinessException(409, "优化任务的简历 SOURCE 已变化，不能覆盖冻结快照");
            }
        }
    }

    private void updateOwnedJobTargetTitle(Long userId, Long jobTargetId, String title, LocalDateTime now) {
        int rows = jobTargetMapper.update(null, new UpdateWrapper<JobTarget>()
                .eq("id", jobTargetId)
                .eq("user_id", userId)
                .set("title", truncate(title.strip(), 200))
                .set("updated_at", now));
        if (rows != 1) {
            throw new BusinessException(404, "目标岗位不存在");
        }
    }

    private String serializePromptSnapshot(JobDescriptionVO parsedJob, EvidenceAnalysis evidenceAnalysis) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "jobParsePromptVersion", valueOrEmpty(parsedJob == null ? null : parsedJob.getPromptVersion()),
                    "evidenceMatchPromptVersion", valueOrEmpty(evidenceAnalysis.getPromptVersion())));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "任务配置快照保存失败");
        }
    }

    private AiSelectionSnapshot requireSelection(AiSelectionSnapshot selection) {
        if (selection == null) {
            throw new BusinessException(500, "优化任务缺少 AI 选择快照");
        }
        if (selection.source() == AiSource.USER_BYOK
                && (selection.credentialId() == null
                || selection.credentialRevision() == null
                || selection.baseUrl().isBlank()
                || selection.model().isBlank()
                || selection.configJson().isBlank())) {
            throw new BusinessException(500, "优化任务的 BYOK 选择快照不完整");
        }
        return selection;
    }

    private AiSelectionSnapshot legacySystemSelection(String providerSnapshot, String modelSnapshot) {
        return new AiSelectionSnapshot(
                AiSource.SYSTEM_DEFAULT,
                normalizeBlank(providerSnapshot),
                null,
                null,
                "",
                normalizeBlank(modelSnapshot),
                "{}",
                null);
    }

    private void applyAiSelection(OptimizationTask task, AiSelectionSnapshot selection) {
        AiSelectionSnapshot safeSelection = requireSelection(selection);
        task.setProviderSnapshot(safeSelection.providerType());
        task.setAiSourceSnapshot(safeSelection.source().name());
        task.setAiProviderSnapshot(safeSelection.providerType());
        task.setAiCredentialId(safeSelection.credentialId());
        task.setAiCredentialIdSnapshot(safeSelection.credentialId());
        task.setAiCredentialRevision(safeSelection.credentialRevision());
        task.setAiBaseUrlSnapshot(safeSelection.baseUrl());
        task.setModelSnapshot(safeSelection.model());
        task.setAiConfigSnapshot(safeSelection.configJson());
    }

    private AiSelectionSnapshot toAiSelection(OptimizationTask task) {
        AiSource source = AiSource.USER_BYOK.name().equals(task.getAiSourceSnapshot())
                ? AiSource.USER_BYOK
                : AiSource.SYSTEM_DEFAULT;
        if (source == AiSource.SYSTEM_DEFAULT) {
            // V19-V22 tasks have no Phase 7 fields and deliberately remain system-default.
            return new AiSelectionSnapshot(
                    AiSource.SYSTEM_DEFAULT,
                    task.getAiProviderSnapshot(),
                    null,
                    null,
                    task.getAiBaseUrlSnapshot(),
                    task.getModelSnapshot(),
                    task.getAiConfigSnapshot(),
                    null);
        }
        if (task.getAiCredentialIdSnapshot() == null
                || task.getAiCredentialRevision() == null
                || isBlank(task.getAiProviderSnapshot())
                || isBlank(task.getAiBaseUrlSnapshot())
                || isBlank(task.getModelSnapshot())
                || isBlank(task.getAiConfigSnapshot())) {
            throw new BusinessException(500, "优化任务的 BYOK 选择快照损坏");
        }
        return new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                task.getAiProviderSnapshot(),
                task.getAiCredentialIdSnapshot(),
                task.getAiCredentialRevision(),
                task.getAiBaseUrlSnapshot(),
                task.getModelSnapshot(),
                task.getAiConfigSnapshot(),
                null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OptimizationTaskVO toVO(
            OptimizationTask task,
            ResumeVersion sourceVersion,
            JobTarget jobTarget,
            Resume resume) {
        return OptimizationTaskVO.builder()
                .optimizationTaskId(task.getId())
                .resumeId(resume.getId())
                .sourceResumeVersionId(task.getSourceResumeVersionId())
                .targetResumeVersionId(task.getTargetResumeVersionId())
                .jobTargetId(task.getJobTargetId())
                .asyncTaskId(task.getAsyncTaskId())
                .analysisResultId(task.getAnalysisResultId())
                .status(task.getStatus())
                .jobTitle(jobTarget.getTitle())
                .resumeName(resume.getDisplayName() != null && !resume.getDisplayName().isBlank()
                        ? resume.getDisplayName() : resume.getOriginalFilename())
                .providerSnapshot(task.getProviderSnapshot())
                .modelSnapshot(task.getModelSnapshot())
                .templateVersion(task.getTemplateVersion())
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
