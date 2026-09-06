package com.winter.airesumeoptimizer.module.export.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.storage.FileStorageException;
import com.winter.airesumeoptimizer.infra.storage.FileStorageService;
import com.winter.airesumeoptimizer.module.export.entity.ExportArtifact;
import com.winter.airesumeoptimizer.module.export.mapper.ExportArtifactMapper;
import com.winter.airesumeoptimizer.module.export.service.ExportArtifactCleanupService;
import com.winter.airesumeoptimizer.module.optimization.entity.JobTarget;
import com.winter.airesumeoptimizer.module.optimization.entity.OptimizationTask;
import com.winter.airesumeoptimizer.module.optimization.entity.ResumeVersion;
import com.winter.airesumeoptimizer.module.optimization.mapper.JobTargetMapper;
import com.winter.airesumeoptimizer.module.optimization.mapper.OptimizationTaskMapper;
import com.winter.airesumeoptimizer.module.optimization.mapper.ResumeVersionMapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class ExportArtifactCleanupServiceImpl implements ExportArtifactCleanupService {

    private static final String DELETE_PENDING = "DELETE_PENDING";

    private final ResumeVersionMapper resumeVersionMapper;
    private final JobTargetMapper jobTargetMapper;
    private final OptimizationTaskMapper optimizationTaskMapper;
    private final ExportArtifactMapper exportArtifactMapper;
    private final FileStorageService fileStorageService;
    private final TransactionTemplate requiresNewTransaction;

    public ExportArtifactCleanupServiceImpl(
            ResumeVersionMapper resumeVersionMapper,
            JobTargetMapper jobTargetMapper,
            OptimizationTaskMapper optimizationTaskMapper,
            ExportArtifactMapper exportArtifactMapper,
            FileStorageService fileStorageService,
            PlatformTransactionManager transactionManager) {
        this.resumeVersionMapper = resumeVersionMapper;
        this.jobTargetMapper = jobTargetMapper;
        this.optimizationTaskMapper = optimizationTaskMapper;
        this.exportArtifactMapper = exportArtifactMapper;
        this.fileStorageService = fileStorageService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void deleteArtifact(Long userId, Long artifactId) {
        ExportArtifact artifact = ownedArtifact(userId, artifactId);
        deleteStorageAndRecord(artifact);
    }

    @Override
    public void deleteArtifactsForResume(Long userId, Long resumeId) {
        if (userId == null || resumeId == null) {
            return;
        }
        List<Long> versionIds = resumeVersionMapper.selectList(new LambdaQueryWrapper<ResumeVersion>()
                        .select(ResumeVersion::getId)
                        .eq(ResumeVersion::getUserId, userId)
                        .eq(ResumeVersion::getResumeId, resumeId))
                .stream().map(ResumeVersion::getId).toList();
        deleteArtifactsForTaskIds(userId, taskIdsForTargetVersions(userId, versionIds));
    }

    @Override
    public void deleteArtifactsForOptimizationTask(Long userId, Long optimizationTaskId) {
        if (userId == null || optimizationTaskId == null) {
            return;
        }
        List<ExportArtifact> artifacts = exportArtifactMapper.selectList(new LambdaQueryWrapper<ExportArtifact>()
                .eq(ExportArtifact::getUserId, userId)
                .eq(ExportArtifact::getOptimizationTaskId, optimizationTaskId));
        for (ExportArtifact artifact : artifacts) {
            deleteStorageAndRecord(artifact);
        }
    }

    @Override
    public void deleteArtifactsForJobDescription(Long userId, Long jobDescriptionId) {
        if (userId == null || jobDescriptionId == null) {
            return;
        }
        List<Long> jobTargetIds = jobTargetMapper.selectList(new LambdaQueryWrapper<JobTarget>()
                        .select(JobTarget::getId)
                        .eq(JobTarget::getUserId, userId)
                        .eq(JobTarget::getLegacyJobDescriptionId, jobDescriptionId))
                .stream().map(JobTarget::getId).toList();
        if (jobTargetIds.isEmpty()) {
            return;
        }
        List<Long> taskIds = optimizationTaskMapper.selectList(new LambdaQueryWrapper<OptimizationTask>()
                        .select(OptimizationTask::getId)
                        .eq(OptimizationTask::getUserId, userId)
                        .in(OptimizationTask::getJobTargetId, jobTargetIds))
                .stream().map(OptimizationTask::getId).toList();
        deleteArtifactsForTaskIds(userId, taskIds);
    }

    private List<Long> taskIdsForTargetVersions(Long userId, List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return optimizationTaskMapper.selectList(new LambdaQueryWrapper<OptimizationTask>()
                        .select(OptimizationTask::getId)
                        .eq(OptimizationTask::getUserId, userId)
                        .in(OptimizationTask::getTargetResumeVersionId, versionIds))
                .stream().map(OptimizationTask::getId).toList();
    }

    private void deleteArtifactsForTaskIds(Long userId, List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        List<ExportArtifact> artifacts = exportArtifactMapper.selectList(new LambdaQueryWrapper<ExportArtifact>()
                .eq(ExportArtifact::getUserId, userId)
                .in(ExportArtifact::getOptimizationTaskId, taskIds));
        for (ExportArtifact artifact : artifacts) {
            deleteStorageAndRecord(artifact);
        }
    }

    private void deleteStorageAndRecord(ExportArtifact artifact) {
        markDeletePending(artifact);
        try {
            fileStorageService.delete(artifact.getStorageKey());
        } catch (FileStorageException exception) {
            log.warn("导出文件删除失败，已保留 DELETE_PENDING 供重试: artifactId={}", artifact.getId());
            throw new BusinessException(500, "导出文件删除失败，已保留记录，请重试");
        }
        try {
            requiresNewTransaction.executeWithoutResult(status -> {
                int rows = exportArtifactMapper.delete(new LambdaQueryWrapper<ExportArtifact>()
                        .eq(ExportArtifact::getId, artifact.getId())
                        .eq(ExportArtifact::getUserId, artifact.getUserId())
                        .eq(ExportArtifact::getStatus, DELETE_PENDING));
                if (rows != 1) {
                    throw new IllegalStateException("导出文件删除记录行数不正确");
                }
            });
        } catch (RuntimeException exception) {
            log.error("导出对象已删除但元数据仍为 DELETE_PENDING，可安全重试: artifactId={}", artifact.getId());
            throw new BusinessException(500, "导出文件删除未完成，请重试");
        }
    }

    private void markDeletePending(ExportArtifact artifact) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> {
                int rows = exportArtifactMapper.update(null, new UpdateWrapper<ExportArtifact>()
                        .eq("id", artifact.getId())
                        .eq("user_id", artifact.getUserId())
                        .set("status", DELETE_PENDING));
                if (rows != 1) {
                    throw new IllegalStateException("导出文件删除状态更新行数不正确");
                }
            });
            artifact.setStatus(DELETE_PENDING);
        } catch (RuntimeException exception) {
            throw new BusinessException(500, "导出文件删除准备失败，请重试");
        }
    }

    private ExportArtifact ownedArtifact(Long userId, Long artifactId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (artifactId == null || artifactId <= 0) {
            throw new BusinessException(400, "导出文件 ID 必须大于 0");
        }
        ExportArtifact artifact = exportArtifactMapper.selectOne(new LambdaQueryWrapper<ExportArtifact>()
                .eq(ExportArtifact::getId, artifactId)
                .eq(ExportArtifact::getUserId, userId));
        if (artifact == null) {
            throw new BusinessException(404, "导出文件不存在");
        }
        return artifact;
    }
}
