package com.winter.airesumeoptimizer.module.observability.mapper;

import com.winter.airesumeoptimizer.module.observability.vo.ProductObservabilitySnapshotVO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Narrow committed-fact report. This is intentionally not an event stream and
 * is not exposed as a user-facing dashboard.
 */
@Mapper
public interface ProductObservabilityMapper {

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM users
                WHERE created_at >= #{fromInclusive} AND created_at < #{toExclusive}) AS registrations,
              (SELECT COUNT(*) FROM resumes
                WHERE created_at >= #{fromInclusive} AND created_at < #{toExclusive}) AS uploaded_resumes,
              (SELECT COUNT(*) FROM async_tasks
                WHERE task_type = 'RESUME_PARSE' AND status = 'SUCCESS'
                  AND finished_at >= #{fromInclusive} AND finished_at < #{toExclusive}) AS resume_preparation_successes,
              (SELECT COUNT(*) FROM async_tasks
                WHERE task_type = 'RESUME_PARSE' AND status IN ('FAILED', 'CANCELLED')
                  AND finished_at >= #{fromInclusive} AND finished_at < #{toExclusive}) AS resume_preparation_failures,
              (SELECT COUNT(*) FROM optimization_tasks
                WHERE status = 'SUCCESS' AND finished_at >= #{fromInclusive} AND finished_at < #{toExclusive}) AS analysis_successes,
              (SELECT COUNT(*) FROM optimization_tasks
                WHERE status IN ('FAILED', 'CANCELLED')
                  AND finished_at >= #{fromInclusive} AND finished_at < #{toExclusive}) AS analysis_failures,
              (SELECT COUNT(*) FROM export_artifacts
                WHERE status = 'READY' AND created_at >= #{fromInclusive} AND created_at < #{toExclusive}) AS successful_exports,
              (SELECT COUNT(*) FROM optimization_tasks task
                WHERE task.status = 'SUCCESS' AND task.finished_at >= #{fromInclusive} AND task.finished_at < #{toExclusive}
                  AND EXISTS (SELECT 1 FROM export_artifacts artifact
                              WHERE artifact.optimization_task_id = task.id AND artifact.status = 'READY')) AS analyses_with_export,
              COALESCE((
                SELECT CAST(AVG(EXTRACT(EPOCH FROM (first_success.finished_at - first_task.created_at)) * 1000) AS BIGINT)
                FROM (SELECT user_id, MIN(created_at) AS created_at FROM optimization_tasks GROUP BY user_id) first_task
                JOIN (SELECT user_id, MIN(finished_at) AS finished_at FROM optimization_tasks
                      WHERE status = 'SUCCESS' GROUP BY user_id) first_success
                  ON first_success.user_id = first_task.user_id
                WHERE first_success.finished_at >= #{fromInclusive} AND first_success.finished_at < #{toExclusive}
              ), 0) AS average_first_successful_analysis_ms,
              (SELECT COALESCE(SUM(provider_dispatch_count), 0) FROM ai_usage_records
                WHERE created_at >= #{fromInclusive} AND created_at < #{toExclusive}) AS provider_attempts,
              (SELECT COUNT(*) FROM ai_usage_records
                WHERE outcome = 'FAILURE' AND created_at >= #{fromInclusive} AND created_at < #{toExclusive}) AS provider_failures,
              (SELECT COALESCE(SUM(total_tokens), 0) FROM ai_usage_records
                WHERE created_at >= #{fromInclusive} AND created_at < #{toExclusive}) AS reported_total_tokens
            """)
    ProductObservabilitySnapshotVO selectSnapshot(
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);
}
