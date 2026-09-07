package com.winter.airesumeoptimizer.module.analysis.service.impl;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGateway;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewaySupport;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.module.analysis.dto.JobAnalysisStartRequestDTO;
import com.winter.airesumeoptimizer.module.analysis.service.JobAnalysisService;
import com.winter.airesumeoptimizer.module.analysis.vo.JobAnalysisStartVO;
import com.winter.airesumeoptimizer.module.evidence.service.EvidenceMatchService;
import com.winter.airesumeoptimizer.module.job.service.JobDescriptionParseService;
import com.winter.airesumeoptimizer.module.job.vo.JobDescriptionVO;
import com.winter.airesumeoptimizer.module.optimization.service.OptimizationTaskService;
import com.winter.airesumeoptimizer.module.optimization.service.OptimizationTaskService.ExecutionContext;
import com.winter.airesumeoptimizer.module.optimization.vo.OptimizationTaskVO;
import com.winter.airesumeoptimizer.module.resume.enums.ResumeQualityStatus;
import com.winter.airesumeoptimizer.module.resume.service.ResumeService;
import com.winter.airesumeoptimizer.module.resume.vo.ResumeParseResultVO;
import com.winter.airesumeoptimizer.module.task.enums.AsyncTaskErrorCode;
import com.winter.airesumeoptimizer.module.task.enums.AsyncTaskType;
import com.winter.airesumeoptimizer.module.task.service.AsyncTaskFailureHandler;
import com.winter.airesumeoptimizer.module.task.service.AsyncTaskService;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class JobAnalysisServiceImpl implements JobAnalysisService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String BIZ_TYPE_OPTIMIZATION_TASK = "OPTIMIZATION_TASK";
    private static final String RESULT_TYPE_OPTIMIZATION_TASK = "OPTIMIZATION_TASK";
    private static final String PROVIDER_SYSTEM_DEFAULT = "SYSTEM_DEFAULT_OPENAI_COMPATIBLE";
    private static final int TITLE_MAX_LENGTH = 200;

    private final ResumeService resumeService;
    private final JobDescriptionParseService jobDescriptionParseService;
    private final EvidenceMatchService evidenceMatchService;
    private final OptimizationTaskService optimizationTaskService;
    private final AsyncTaskService asyncTaskService;
    private final AsyncTaskFailureHandler asyncTaskFailureHandler;
    private final AiGateway aiGateway;
    private final TaskExecutor taskExecutor;

    public JobAnalysisServiceImpl(
            ResumeService resumeService,
            JobDescriptionParseService jobDescriptionParseService,
            EvidenceMatchService evidenceMatchService,
            OptimizationTaskService optimizationTaskService,
            AsyncTaskService asyncTaskService,
            AsyncTaskFailureHandler asyncTaskFailureHandler,
            AiGateway aiGateway,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.resumeService = resumeService;
        this.jobDescriptionParseService = jobDescriptionParseService;
        this.evidenceMatchService = evidenceMatchService;
        this.optimizationTaskService = optimizationTaskService;
        this.asyncTaskService = asyncTaskService;
        this.asyncTaskFailureHandler = asyncTaskFailureHandler;
        this.aiGateway = aiGateway;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public JobAnalysisStartVO start(Long userId, JobAnalysisStartRequestDTO request) {
        if (request == null) {
            throw new BusinessException(400, "岗位分析请求不能为空");
        }
        validateResumeId(request.getResumeId());
        if (request.getJobDescription() == null || request.getJobDescription().isBlank()) {
            throw new BusinessException(400, "目标岗位 JD 不能为空");
        }
        if (request.getJobDescription().length() > 10000) {
            throw new BusinessException(400, "目标岗位 JD 不能超过 10000 个字符");
        }
        resumeService.getDetail(userId, request.getResumeId());

        AiSelectionSnapshot selection = AiGatewaySupport.selectionForNewTask(
                aiGateway,
                userId,
                "OPTIMIZATION_TASK_SELECTION");
        OptimizationTaskVO optimizationTask = isLegacySelection(selection)
                ? optimizationTaskService.create(
                        userId,
                        request.getResumeId(),
                        deriveTitle(request.getJobDescription()),
                        request.getJobDescription(),
                        PROVIDER_SYSTEM_DEFAULT,
                        selection.model())
                : optimizationTaskService.create(
                        userId,
                        request.getResumeId(),
                        deriveTitle(request.getJobDescription()),
                        request.getJobDescription(),
                        selection);
        return submitAnalysis(userId, optimizationTask.getOptimizationTaskId());
    }

    @Override
    public JobAnalysisStartVO retry(Long userId, Long optimizationTaskId) {
        return submitAnalysis(userId, optimizationTaskId);
    }

    @Override
    public JobAnalysisStartVO retryLegacy(Long userId, Long resumeId, Long jobDescriptionId) {
        validateResumeId(resumeId);
        if (jobDescriptionId == null || jobDescriptionId <= 0) {
            throw new BusinessException(400, "目标岗位 ID 必须大于 0");
        }
        OptimizationTaskVO task;
        try {
            task = optimizationTaskService.findByLegacyInputs(userId, resumeId, jobDescriptionId);
        } catch (BusinessException exception) {
            if (exception.getCode() != 404) {
                throw exception;
            }
            AiSelectionSnapshot selection = AiGatewaySupport.selectionForNewTask(
                    aiGateway,
                    userId,
                    "OPTIMIZATION_TASK_SELECTION");
            task = isLegacySelection(selection)
                    ? optimizationTaskService.createFromExisting(
                            userId,
                            resumeId,
                            jobDescriptionId,
                            PROVIDER_SYSTEM_DEFAULT,
                            selection.model())
                    : optimizationTaskService.createFromExisting(
                            userId,
                            resumeId,
                            jobDescriptionId,
                            selection);
        }
        return submitAnalysis(userId, task.getOptimizationTaskId());
    }

    private JobAnalysisStartVO submitAnalysis(Long userId, Long optimizationTaskId) {
        OptimizationTaskVO formalTask = optimizationTaskService.get(userId, optimizationTaskId);
        if (STATUS_SUCCESS.equals(formalTask.getStatus())) {
            throw new BusinessException(409, "已完成的优化任务不能重试");
        }
        if (formalTask.getAsyncTaskId() != null
                && (STATUS_PENDING.equals(formalTask.getStatus()) || STATUS_RUNNING.equals(formalTask.getStatus()))) {
            throw new BusinessException(409, "岗位分析正在进行中");
        }
        ExecutionContext context = optimizationTaskService.getExecutionContext(userId, optimizationTaskId);
        if (context.aiSelection() != null
                && context.aiSelection().source() == com.winter.airesumeoptimizer.infra.ai.AiSource.SYSTEM_DEFAULT) {
            throw new BusinessException(
                    409,
                    "这个历史任务使用的是已停用的旧 AI 配置。请使用自己的 API 新建一个岗位优化任务。");
        }
        Long asyncTaskId = asyncTaskService.createTask(
                userId,
                AsyncTaskType.MATCH_ANALYSIS,
                BIZ_TYPE_OPTIMIZATION_TASK,
                optimizationTaskId);
        try {
            optimizationTaskService.attachAsyncTask(userId, optimizationTaskId, asyncTaskId);
        } catch (RuntimeException exception) {
            asyncTaskService.markFailed(asyncTaskId, "DUPLICATE_SUBMISSION", "岗位分析已在进行中或已经完成");
            throw exception;
        }
        try {
            taskExecutor.execute(() -> runAnalysis(asyncTaskId, userId, context));
        } catch (RejectedExecutionException exception) {
            try {
                optimizationTaskService.markFailed(
                        userId,
                        optimizationTaskId,
                        AsyncTaskErrorCode.TASK_REJECTED.name(),
                        AsyncTaskErrorCode.TASK_REJECTED.getUserMessage());
            } finally {
                asyncTaskFailureHandler.markFailed(asyncTaskId, AsyncTaskErrorCode.TASK_REJECTED, exception);
            }
        }

        return JobAnalysisStartVO.builder()
                .taskId(asyncTaskId)
                .optimizationTaskId(optimizationTaskId)
                .sourceResumeVersionId(context.sourceResumeVersionId())
                .targetResumeVersionId(context.targetResumeVersionId())
                .jobTargetId(context.jobTargetId())
                .resumeId(context.resumeId())
                .jobDescriptionId(context.jobDescriptionId())
                .build();
    }

    private void runAnalysis(Long asyncTaskId, Long userId, ExecutionContext context) {
        boolean formalTaskCompleted = false;
        try {
            optimizationTaskService.markRunning(userId, context.optimizationTaskId());
            asyncTaskService.markRunning(asyncTaskId, "正在读取岗位要求");
            asyncTaskService.updateStage(asyncTaskId, "正在准备简历内容");
            if (context.frozenResumeSnapshot() == null || context.frozenResumeSnapshot().isBlank()) {
                ResumeParseResultVO resumeParseResult = context.aiSelection() == null
                        ? ensureResumeReadyLegacy(userId, context.resumeId())
                        : ensureResumeReady(userId, context.resumeId(), context.aiSelection());
                if (!isConfirmedResume(resumeParseResult)) {
                    failTask(
                            asyncTaskId,
                            userId,
                            context.optimizationTaskId(),
                            AsyncTaskErrorCode.FILE_PARSE_FAILED,
                            resumeFailureMessage(resumeParseResult));
                    return;
                }
                // Evidence / SOURCE / TARGET 只接收已经过质量门的 canonical 文档；
                // structured_json 仍是候选解析产物，不能进入正式任务快照。
                optimizationTaskService.captureResumeSnapshot(
                        userId,
                        context.optimizationTaskId(),
                        resumeParseResult.getCanonicalDocument());
            }
            // Retry 使用任务既有冻结快照；即使当前 Resume 之后重新解析，也不回写历史 Task。

            asyncTaskService.updateStage(asyncTaskId, "正在理解岗位要求");
            JobDescriptionVO parsedJob = jobDescriptionParseService.parse(
                    userId,
                    context.jobDescriptionId(),
                    context.aiSelection(),
                    context.optimizationTaskId());
            if (!STATUS_SUCCESS.equals(parsedJob.getParseStatus())) {
                if (context.aiSelection() != null && context.aiSelection().isUserByok()) {
                    failTask(
                            asyncTaskId,
                            userId,
                            context.optimizationTaskId(),
                            AiFailureCode.SCHEMA_INVALID.name(),
                            firstPresent(parsedJob.getErrorMessage(), "未能理解岗位要求"));
                    return;
                }
                failTask(
                        asyncTaskId,
                        userId,
                        context.optimizationTaskId(),
                        AsyncTaskErrorCode.AI_RESPONSE_INVALID,
                        firstPresent(parsedJob.getErrorMessage(), "未能理解岗位要求"));
                return;
            }

            asyncTaskService.updateStage(asyncTaskId, "正在核对岗位要求与简历内容");
            try {
                if (context.aiSelection() == null) {
                    evidenceMatchService.analyze(
                            userId,
                            context.optimizationTaskId(),
                            parsedJob);
                } else {
                    evidenceMatchService.analyze(
                            userId,
                            context.optimizationTaskId(),
                            parsedJob,
                            context.aiSelection());
                }
            } catch (BusinessException exception) {
                if (context.aiSelection() != null && context.aiSelection().isUserByok()) {
                    failTask(
                            asyncTaskId,
                            userId,
                            context.optimizationTaskId(),
                            AiFailureCode.SCHEMA_INVALID.name(),
                            firstPresent(exception.getMessage(), "岗位分析失败"));
                    return;
                }
                failTask(
                        asyncTaskId,
                        userId,
                        context.optimizationTaskId(),
                        AsyncTaskErrorCode.AI_RESPONSE_INVALID,
                        firstPresent(exception.getMessage(), "岗位分析失败"));
                return;
            }

            asyncTaskService.updateStage(asyncTaskId, "正在整理分析结果");
            formalTaskCompleted = true;
            asyncTaskService.markSuccess(
                    asyncTaskId,
                    RESULT_TYPE_OPTIMIZATION_TASK,
                    context.optimizationTaskId(),
                    firstPresent(parsedJob.getTitle(), "岗位分析完成"));
        } catch (RuntimeException exception) {
            if (exception instanceof AiGatewayException gatewayException) {
                try {
                    if (!formalTaskCompleted) {
                        optimizationTaskService.markFailed(
                                userId,
                                context.optimizationTaskId(),
                                gatewayException.getFailureCode().name(),
                                gatewayException.getMessage());
                    }
                } finally {
                    asyncTaskService.markFailed(
                            asyncTaskId,
                            gatewayException.getFailureCode().name(),
                            gatewayException.getMessage());
                }
                return;
            }
            try {
                if (!formalTaskCompleted) {
                    optimizationTaskService.markFailed(
                            userId,
                            context.optimizationTaskId(),
                            AsyncTaskErrorCode.UNKNOWN_ERROR.name(),
                            AsyncTaskErrorCode.UNKNOWN_ERROR.getUserMessage());
                }
            } finally {
                asyncTaskFailureHandler.markFailed(asyncTaskId, null, exception);
            }
        }
    }

    private void failTask(
            Long asyncTaskId,
            Long userId,
            Long optimizationTaskId,
            String errorCode,
            String message) {
        optimizationTaskService.markFailed(userId, optimizationTaskId, errorCode, message);
        asyncTaskService.markFailed(asyncTaskId, errorCode, message);
    }

    private void failTask(
            Long asyncTaskId,
            Long userId,
            Long optimizationTaskId,
            AsyncTaskErrorCode errorCode,
            String message) {
        optimizationTaskService.markFailed(userId, optimizationTaskId, errorCode.name(), message);
        asyncTaskService.markFailed(asyncTaskId, errorCode.name(), message);
    }

    private ResumeParseResultVO ensureResumeReadyLegacy(Long userId, Long resumeId) {
        try {
            ResumeParseResultVO existing = resumeService.getParseResult(userId, resumeId);
            if (isConfirmedResume(existing)
                    || ResumeQualityStatus.QUALITY_NEEDS_REVIEW.equals(existing.getQualityStatus())) {
                return existing;
            }
        } catch (BusinessException exception) {
            if (exception.getCode() != 404) {
                throw exception;
            }
        }
        return resumeService.parse(userId, resumeId);
    }

    private ResumeParseResultVO ensureResumeReady(
            Long userId,
            Long resumeId,
            AiSelectionSnapshot selection) {
        try {
            ResumeParseResultVO existing = resumeService.getParseResult(userId, resumeId);
            if (isConfirmedResume(existing)
                    || ResumeQualityStatus.QUALITY_NEEDS_REVIEW.equals(existing.getQualityStatus())) {
                return existing;
            }
        } catch (BusinessException exception) {
            if (exception.getCode() != 404) {
                throw exception;
            }
        }
        return resumeService.parseWithSelection(userId, resumeId, selection);
    }

    private boolean isConfirmedResume(ResumeParseResultVO result) {
        return result != null
                && STATUS_SUCCESS.equals(result.getParseStatus())
                && ResumeQualityStatus.QUALITY_READY.equals(result.getQualityStatus())
                && result.getCanonicalDocument() != null
                && !result.getCanonicalDocument().isBlank();
    }

    private String resumeFailureMessage(ResumeParseResultVO result) {
        if (result != null && ResumeQualityStatus.QUALITY_NEEDS_REVIEW.equals(result.getQualityStatus())) {
            return "简历内容存在待确认项，请先完成确认";
        }
        return firstPresent(result == null ? null : result.getErrorMessage(), "未能读取简历内容");
    }

    private void validateResumeId(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(400, "请选择简历");
        }
    }

    private boolean isLegacySelection(AiSelectionSnapshot selection) {
        return selection != null
                && selection.source() == com.winter.airesumeoptimizer.infra.ai.AiSource.SYSTEM_DEFAULT
                && (selection.baseUrl() == null || selection.baseUrl().isBlank());
    }

    private String deriveTitle(String jobDescription) {
        String normalized = jobDescription == null ? "" : jobDescription.strip();
        if (normalized.isEmpty()) {
            return "目标岗位";
        }
        String firstLine = normalized.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("目标岗位");
        return firstLine.length() <= TITLE_MAX_LENGTH
                ? firstLine
                : firstLine.substring(0, TITLE_MAX_LENGTH);
    }

    private String firstPresent(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
