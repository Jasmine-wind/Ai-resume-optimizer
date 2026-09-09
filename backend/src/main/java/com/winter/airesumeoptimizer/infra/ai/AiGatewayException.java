package com.winter.airesumeoptimizer.infra.ai;

/** Safe, provider-independent failure. Never stores a raw provider body or cause. */
public class AiGatewayException extends RuntimeException {

    private final AiFailureCode failureCode;
    private final boolean retryable;
    private final long retryAfterMillis;
    private final int providerDispatchCount;
    private final AiUnsupportedParameter unsupportedParameter;
    private final AiProviderCompatibilityProfile retryProfile;

    public AiGatewayException(AiFailureCode failureCode, String safeMessage) {
        this(failureCode, safeMessage, false, 0L, 0, null);
    }

    public AiGatewayException(AiFailureCode failureCode, String safeMessage, boolean retryable) {
        this(failureCode, safeMessage, retryable, 0L, 0, null, null);
    }

    public AiGatewayException(
            AiFailureCode failureCode,
            String safeMessage,
            boolean retryable,
            long retryAfterMillis) {
        this(failureCode, safeMessage, retryable, retryAfterMillis, 0, null, null);
    }

    public AiGatewayException(
            AiFailureCode failureCode,
            String safeMessage,
            boolean retryable,
            long retryAfterMillis,
            int providerDispatchCount,
            AiUnsupportedParameter unsupportedParameter) {
        this(failureCode, safeMessage, retryable, retryAfterMillis, providerDispatchCount,
                unsupportedParameter, null);
    }

    private AiGatewayException(
            AiFailureCode failureCode,
            String safeMessage,
            boolean retryable,
            long retryAfterMillis,
            int providerDispatchCount,
            AiUnsupportedParameter unsupportedParameter,
            AiProviderCompatibilityProfile retryProfile) {
        super(safeMessage == null || safeMessage.isBlank() ? "AI 服务调用失败" : safeMessage);
        this.failureCode = failureCode == null ? AiFailureCode.PROVIDER_UNAVAILABLE : failureCode;
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0L, Math.min(2000L, retryAfterMillis));
        this.providerDispatchCount = Math.max(0, providerDispatchCount);
        this.unsupportedParameter = unsupportedParameter;
        this.retryProfile = retryProfile;
    }

    public AiFailureCode getFailureCode() {
        return failureCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    public int getProviderDispatchCount() {
        return providerDispatchCount;
    }

    /** Compatibility alias; new code should use the explicit provider name. */
    public int getDispatchCount() {
        return providerDispatchCount;
    }

    public AiUnsupportedParameter getUnsupportedParameter() {
        return unsupportedParameter;
    }

    public AiProviderCompatibilityProfile getRetryProfile() {
        return retryProfile;
    }

    public AiGatewayException withProviderDispatchCount(int count) {
        return new AiGatewayException(
                failureCode, getMessage(), retryable, retryAfterMillis, count, unsupportedParameter, retryProfile);
    }

    public AiGatewayException withRetryProfile(AiProviderCompatibilityProfile profile) {
        return new AiGatewayException(
                failureCode, getMessage(), retryable, retryAfterMillis,
                providerDispatchCount, unsupportedParameter, profile);
    }

    /** Compatibility alias; new code should use withProviderDispatchCount. */
    public AiGatewayException withDispatchCount(int count) {
        return withProviderDispatchCount(count);
    }
}
