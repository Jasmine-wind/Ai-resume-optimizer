package com.winter.airesumeoptimizer.infra.ai;

/**
 * Usage metrics keep Gateway retries separate from actual HTTP dispatches made
 * by the provider adapter's compatibility state machine.
 */
public record AiUsageMetrics(
        Long inputTokens,
        Long outputTokens,
        long latencyMs,
        int gatewayAttemptCount,
        int providerDispatchCount) {

    /** Compatibility constructor: one Gateway attempt with the supplied dispatch count. */
    public AiUsageMetrics(Long inputTokens, Long outputTokens, long latencyMs, int providerDispatchCount) {
        this(inputTokens, outputTokens, latencyMs, 1, providerDispatchCount);
    }

    public static AiUsageMetrics empty(long latencyMs, int providerDispatchCount) {
        int dispatches = Math.max(1, providerDispatchCount);
        return new AiUsageMetrics(null, null, Math.max(0, latencyMs), 1, dispatches);
    }
}
