package com.winter.airesumeoptimizer.infra.ai;

/** Safe allowlist extracted from a provider error; raw provider text is never retained. */
public enum AiUnsupportedParameter {
    TEMPERATURE,
    MAX_TOKENS,
    MAX_COMPLETION_TOKENS,
    THINKING,
    ENABLE_THINKING,
    REASONING_EFFORT
}
