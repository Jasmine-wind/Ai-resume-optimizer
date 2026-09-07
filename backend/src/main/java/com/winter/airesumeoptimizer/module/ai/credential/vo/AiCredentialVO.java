package com.winter.airesumeoptimizer.module.ai.credential.vo;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AiCredentialVO {

    private final String providerType;
    private final String baseUrl;
    private final String model;
    private final Map<String, Object> config;
    private final String status;
    private final boolean configured;
    private final boolean apiKeyConfigured;
    private final String maskedApiKey;
    private final boolean credentialStorageAvailable;
    private final Long credentialRevision;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
