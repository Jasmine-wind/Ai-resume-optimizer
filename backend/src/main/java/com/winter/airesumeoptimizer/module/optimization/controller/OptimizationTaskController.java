package com.winter.airesumeoptimizer.module.optimization.controller;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.common.result.Result;
import com.winter.airesumeoptimizer.module.analysis.assembler.AnalysisVoAssembler;
import com.winter.airesumeoptimizer.module.analysis.service.JobAnalysisService;
import com.winter.airesumeoptimizer.module.analysis.vo.AiJobMatchResultVO;
import com.winter.airesumeoptimizer.module.analysis.vo.JobAnalysisStartVO;
import com.winter.airesumeoptimizer.module.evidence.service.EvidenceMatchService;
import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceAnalysisResultVO;
import com.winter.airesumeoptimizer.module.optimization.service.OptimizationTaskService;
import com.winter.airesumeoptimizer.module.optimization.vo.OptimizationAnalysisResultVO;
import com.winter.airesumeoptimizer.module.optimization.vo.OptimizationTaskVO;
import com.winter.airesumeoptimizer.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/optimization-tasks")
@Validated
@Tag(name = "Optimization Task", description = "岗位定向优化正式业务任务")
@SecurityRequirement(name = "bearerAuth")
public class OptimizationTaskController {

    private static final String ANALYSIS_MODE_EVIDENCE = "EVIDENCE";
    private static final String ANALYSIS_MODE_LEGACY_COMPAT = "LEGACY_COMPAT";

    private final OptimizationTaskService optimizationTaskService;
    private final JobAnalysisService jobAnalysisService;
    private final EvidenceMatchService evidenceMatchService;
    private final AnalysisVoAssembler analysisVoAssembler;

    public OptimizationTaskController(
            OptimizationTaskService optimizationTaskService,
            JobAnalysisService jobAnalysisService,
            EvidenceMatchService evidenceMatchService,
            AnalysisVoAssembler analysisVoAssembler) {
        this.optimizationTaskService = optimizationTaskService;
        this.jobAnalysisService = jobAnalysisService;
        this.evidenceMatchService = evidenceMatchService;
        this.analysisVoAssembler = analysisVoAssembler;
    }

    @GetMapping("/recent")
    @Operation(summary = "读取最近岗位优化")
    public Result<java.util.List<OptimizationTaskVO>> recent(
            @RequestParam(defaultValue = "5") @Positive(message = "limit 必须大于 0") @Max(value = 20, message = "limit 不能超过 20") Integer limit,
            Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return Result.success(optimizationTaskService.listRecent(user.getUserId(), limit));
    }

    @DeleteMapping("/{optimizationTaskId}")
    @Operation(summary = "删除岗位优化记录", description = "只删除当前用户的岗位优化任务及其岗位派生版本，不删除原始简历")
    public Result<Void> delete(
            @PathVariable @Positive(message = "优化任务 ID 必须大于 0") Long optimizationTaskId,
            Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        optimizationTaskService.delete(user.getUserId(), optimizationTaskId);
        return Result.success("已删除岗位优化记录", null);
    }

    @GetMapping("/{optimizationTaskId}")
    @Operation(summary = "查看优化任务", description = "按当前用户读取任务、版本来源和执行快照元数据")
    public Result<OptimizationTaskVO> get(
            @PathVariable @Positive(message = "优化任务 ID 必须大于 0") Long optimizationTaskId,
            Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return Result.success(optimizationTaskService.get(user.getUserId(), optimizationTaskId));
    }

    @PostMapping("/{optimizationTaskId}/retry")
    @Operation(summary = "重试优化任务", description = "复用正式任务中已保存的简历版本与目标岗位输入")
    public Result<JobAnalysisStartVO> retry(
            @PathVariable @Positive(message = "优化任务 ID 必须大于 0") Long optimizationTaskId,
            Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return Result.success(
                "岗位分析已重新开始",
                jobAnalysisService.retry(user.getUserId(), optimizationTaskId));
    }

    @GetMapping("/{optimizationTaskId}/analysis-result")
    @Operation(summary = "查看岗位分析结果", description = "优先返回正式岗位证据分析；历史任务无正式分析时兼容读取旧匹配结果")
    public Result<OptimizationAnalysisResultVO> getAnalysisResult(
            @PathVariable @Positive(message = "优化任务 ID 必须大于 0") Long optimizationTaskId,
            Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        OptimizationTaskVO task = optimizationTaskService.get(user.getUserId(), optimizationTaskId);
        EvidenceAnalysisResultVO evidenceAnalysis =
                evidenceMatchService.getResult(user.getUserId(), optimizationTaskId);
        AiJobMatchResultVO legacyAnalysis = null;
        if (evidenceAnalysis == null) {
            legacyAnalysis = loadLegacyAnalysis(user.getUserId(), optimizationTaskId);
        }
        return Result.success(OptimizationAnalysisResultVO.builder()
                .optimizationTaskId(task.getOptimizationTaskId())
                .resumeId(task.getResumeId())
                .sourceResumeVersionId(task.getSourceResumeVersionId())
                .sourceCanonicalDocument(evidenceAnalysis == null
                        ? null
                        : optimizationTaskService.getFrozenSourceCanonicalDocument(
                                user.getUserId(), optimizationTaskId))
                .targetResumeVersionId(task.getTargetResumeVersionId())
                .jobTargetId(task.getJobTargetId())
                .status(task.getStatus())
                .jobTitle(task.getJobTitle())
                .resumeName(task.getResumeName())
                .analysisMode(evidenceAnalysis != null ? ANALYSIS_MODE_EVIDENCE : ANALYSIS_MODE_LEGACY_COMPAT)
                .evidenceAnalysis(evidenceAnalysis)
                .legacyAnalysis(legacyAnalysis)
                .build());
    }

    private AiJobMatchResultVO loadLegacyAnalysis(Long userId, Long optimizationTaskId) {
        try {
            return analysisVoAssembler.toAiJobMatchResultVO(
                    optimizationTaskService.getLegacyAnalysisResult(userId, optimizationTaskId));
        } catch (BusinessException exception) {
            if (exception.getCode() != null && exception.getCode() == 404) {
                return null;
            }
            throw exception;
        }
    }
}
