package com.winter.airesumeoptimizer.module.ai.usage.service;

import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiUsageMetrics;

public interface AiUsageRecorder {

    void recordSuccess(AiInvocationContext context, AiSelectionSnapshot selection, AiUsageMetrics usage);

    void recordFailure(
            AiInvocationContext context,
            AiSelectionSnapshot selection,
            AiFailureCode failureCode,
            long latencyMs,
            int providerDispatchCount);

    default void recordFailure(
            AiInvocationContext context,
            AiSelectionSnapshot selection,
            AiFailureCode failureCode,
            long latencyMs,
            int gatewayAttemptCount,
            int providerDispatchCount) {
        recordFailure(context, selection, failureCode, latencyMs, providerDispatchCount);
    }
}
