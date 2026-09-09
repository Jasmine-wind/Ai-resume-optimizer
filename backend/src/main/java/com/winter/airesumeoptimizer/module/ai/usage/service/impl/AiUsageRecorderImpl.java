package com.winter.airesumeoptimizer.module.ai.usage.service.impl;

import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiUsageMetrics;
import com.winter.airesumeoptimizer.module.ai.usage.entity.AiUsageRecord;
import com.winter.airesumeoptimizer.module.ai.usage.service.AiUsageRecordPersistence;
import com.winter.airesumeoptimizer.module.ai.usage.service.AiUsageRecorder;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/** Best-effort ledger: its failure is deliberately isolated by the gateway. */
@Service
public class AiUsageRecorderImpl implements AiUsageRecorder {

    private final AiUsageRecordPersistence usageRecordPersistence;

    public AiUsageRecorderImpl(AiUsageRecordPersistence usageRecordPersistence) {
        this.usageRecordPersistence = usageRecordPersistence;
    }

    @Override
    public void recordSuccess(AiInvocationContext context, AiSelectionSnapshot selection, AiUsageMetrics usage) {
        insert(context, selection, "SUCCESS", null, usage.latencyMs(), usage.inputTokens(), usage.outputTokens(),
                usage.gatewayAttemptCount(), usage.providerDispatchCount());
    }

    @Override
    public void recordFailure(
            AiInvocationContext context,
            AiSelectionSnapshot selection,
            AiFailureCode failureCode,
            long latencyMs,
            int providerDispatchCount) {
        recordFailure(context, selection, failureCode, latencyMs, 1, providerDispatchCount);
    }

    @Override
    public void recordFailure(
            AiInvocationContext context,
            AiSelectionSnapshot selection,
            AiFailureCode failureCode,
            long latencyMs,
            int gatewayAttemptCount,
            int providerDispatchCount) {
        insert(context, selection, "FAILURE", failureCode, latencyMs, null, null,
                gatewayAttemptCount, providerDispatchCount);
    }

    private void insert(
            AiInvocationContext context,
            AiSelectionSnapshot selection,
            String outcome,
            AiFailureCode failureCode,
            long latencyMs,
            Long promptTokens,
            Long completionTokens,
            int gatewayAttemptCount,
            int providerDispatchCount) {
        AiUsageRecord record = new AiUsageRecord();
        record.setUserId(context.userId());
        record.setOptimizationTaskId(context.taskId());
        record.setOperation(context.operation());
        record.setSource(selection.source().name());
        record.setProvider(selection.providerType());
        record.setModel(selection.model());
        record.setCredentialRevision(selection.credentialRevision());
        record.setOutcome(outcome);
        record.setFailureCode(failureCode == null ? null : failureCode.name());
        record.setLatencyMs(Math.max(0L, latencyMs));
        record.setPromptTokens(toInt(promptTokens));
        record.setCompletionTokens(toInt(completionTokens));
        record.setTotalTokens(sum(promptTokens, completionTokens));
        record.setGatewayAttemptCount(Math.max(1, gatewayAttemptCount));
        record.setProviderDispatchCount(Math.max(1, providerDispatchCount));
        record.setCreatedAt(LocalDateTime.now());
        usageRecordPersistence.persist(record);
    }

    private Integer toInt(Long value) {
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private Integer sum(Long first, Long second) {
        if (first == null || second == null || first < 0 || second < 0) {
            return null;
        }
        long total = first + second;
        return total > Integer.MAX_VALUE ? null : (int) total;
    }
}
