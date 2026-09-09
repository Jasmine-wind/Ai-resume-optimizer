package com.winter.airesumeoptimizer.infra.ai;

public record AiProviderResponse(
        String text,
        Long inputTokens,
        Long outputTokens,
        int providerDispatchCount) {

    public AiProviderResponse(String text, Long inputTokens, Long outputTokens) {
        this(text, inputTokens, outputTokens, 1);
    }

    public AiProviderResponse {
        text = text == null ? "" : text;
        providerDispatchCount = Math.max(1, providerDispatchCount);
    }

    /** Compatibility alias for callers compiled against the initial field name. */
    public int dispatchCount() {
        return providerDispatchCount;
    }
}
