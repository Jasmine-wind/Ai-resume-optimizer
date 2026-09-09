package com.winter.airesumeoptimizer.infra.ai;

import java.time.Duration;
import java.util.List;

/** Internal adapter request. Contains the decrypted key only inside infra/ai. */
public record AiProviderRequest(
        String apiKey,
        String baseUrl,
        String model,
        Double temperature,
        Integer maxTokens,
        Duration timeout,
        List<AiChatMessage> messages,
        AiProviderCompatibilityProfile compatibilityProfile,
        boolean compatibilityProfilePinned) {

    public AiProviderRequest(
            String apiKey,
            String baseUrl,
            String model,
            Double temperature,
            Integer maxTokens,
            Duration timeout,
            List<AiChatMessage> messages) {
        this(apiKey, baseUrl, model, temperature, maxTokens, timeout, messages,
                AiProviderCompatibilityProfile.standard(), false);
    }

    public AiProviderRequest(
            String apiKey,
            String baseUrl,
            String model,
            Double temperature,
            Integer maxTokens,
            Duration timeout,
            List<AiChatMessage> messages,
            AiProviderCompatibilityProfile compatibilityProfile) {
        this(apiKey, baseUrl, model, temperature, maxTokens, timeout, messages,
                compatibilityProfile, false);
    }

    public AiProviderRequest {
        timeout = timeout == null ? Duration.ofSeconds(90) : timeout;
        messages = messages == null ? List.of() : List.copyOf(messages);
        compatibilityProfile = compatibilityProfile == null
                ? AiProviderCompatibilityProfile.standard()
                : compatibilityProfile;
    }

    public AiProviderRequest withCompatibilityProfile(AiProviderCompatibilityProfile profile) {
        return new AiProviderRequest(
                apiKey, baseUrl, model, temperature, maxTokens, timeout, messages, profile, false);
    }

    public AiProviderRequest withPinnedCompatibilityProfile(AiProviderCompatibilityProfile profile) {
        return new AiProviderRequest(
                apiKey, baseUrl, model, temperature, maxTokens, timeout, messages, profile, true);
    }

    public AiProviderRequest withTimeout(Duration value) {
        return new AiProviderRequest(
                apiKey, baseUrl, model, temperature, maxTokens, value, messages,
                compatibilityProfile, compatibilityProfilePinned);
    }
}
