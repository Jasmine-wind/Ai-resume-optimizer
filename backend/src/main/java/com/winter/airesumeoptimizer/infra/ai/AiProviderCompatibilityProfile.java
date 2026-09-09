package com.winter.airesumeoptimizer.infra.ai;

/**
 * Internal, provider-neutral Chat Completions dialect. It is deliberately not
 * part of the credential or frontend contract.
 */
public record AiProviderCompatibilityProfile(
        TokenParameter tokenParameter,
        TemperatureMode temperatureMode,
        ReasoningControl reasoningControl) {

    public enum TokenParameter {
        MAX_TOKENS,
        MAX_COMPLETION_TOKENS,
        OMIT
    }

    public enum TemperatureMode {
        INCLUDE,
        OMIT
    }

    public enum ReasoningControl {
        NONE,
        THINKING_DISABLED,
        ENABLE_THINKING_FALSE,
        REASONING_EFFORT_LOW
    }

    public AiProviderCompatibilityProfile {
        tokenParameter = tokenParameter == null ? TokenParameter.MAX_TOKENS : tokenParameter;
        temperatureMode = temperatureMode == null ? TemperatureMode.INCLUDE : temperatureMode;
        reasoningControl = reasoningControl == null ? ReasoningControl.NONE : reasoningControl;
    }

    public static AiProviderCompatibilityProfile standard() {
        return new AiProviderCompatibilityProfile(
                TokenParameter.MAX_TOKENS,
                TemperatureMode.INCLUDE,
                ReasoningControl.NONE);
    }

    public AiProviderCompatibilityProfile withTokenParameter(TokenParameter value) {
        return new AiProviderCompatibilityProfile(value, temperatureMode, reasoningControl);
    }

    public AiProviderCompatibilityProfile withTemperatureMode(TemperatureMode value) {
        return new AiProviderCompatibilityProfile(tokenParameter, value, reasoningControl);
    }

    public AiProviderCompatibilityProfile withReasoningControl(ReasoningControl value) {
        return new AiProviderCompatibilityProfile(tokenParameter, temperatureMode, value);
    }
}
