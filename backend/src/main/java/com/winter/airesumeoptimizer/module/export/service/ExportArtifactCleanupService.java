package com.winter.airesumeoptimizer.module.export.service;

/**
 * 导出物删除与父资源级联前清理 seam。
 *
 * <p>删除采用持久化 DELETE_PENDING → 对象删除 → 元数据删除。任何失败都保留可重试依据；
 * 父资源只有在全部导出物完成该流程后才能继续删除。
 */
public interface ExportArtifactCleanupService {

    void deleteArtifact(Long userId, Long artifactId);

    void deleteArtifactsForResume(Long userId, Long resumeId);

    /** 清理单个岗位优化任务生成的 PDF 派生物，供任务删除前调用。 */
    void deleteArtifactsForOptimizationTask(Long userId, Long optimizationTaskId);

    void deleteArtifactsForJobDescription(Long userId, Long jobDescriptionId);
}
