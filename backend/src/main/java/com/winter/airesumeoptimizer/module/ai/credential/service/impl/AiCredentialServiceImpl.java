package com.winter.airesumeoptimizer.module.ai.credential.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.winter.airesumeoptimizer.infra.ai.AiClientProperties;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiGenerationConfig;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.infra.ai.transport.BaseUrlPolicy;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundTransportException;
import com.winter.airesumeoptimizer.module.ai.credential.dto.AiCredentialUpsertRequestDTO;
import com.winter.airesumeoptimizer.module.ai.credential.entity.AiProviderCredential;
import com.winter.airesumeoptimizer.module.ai.credential.mapper.AiProviderCredentialMapper;
import com.winter.airesumeoptimizer.module.ai.credential.service.AiCredentialService;
import com.winter.airesumeoptimizer.module.ai.credential.service.CredentialCipher;
import com.winter.airesumeoptimizer.module.ai.credential.service.DecryptedCredentialMaterial;
import com.winter.airesumeoptimizer.module.ai.credential.vo.AiCredentialVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiCredentialServiceImpl implements AiCredentialService {

    private static final String PROVIDER_TYPE = AiSelectionSnapshot.OPENAI_COMPATIBLE;
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    private final AiProviderCredentialMapper credentialMapper;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final BaseUrlPolicy baseUrlPolicy;
    private final AiClientProperties systemProperties;

    @Autowired
    public AiCredentialServiceImpl(
            AiProviderCredentialMapper credentialMapper,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            AiClientProperties systemProperties) {
        this(credentialMapper, credentialCipher, objectMapper, new BaseUrlPolicy(), systemProperties);
    }

    AiCredentialServiceImpl(
            AiProviderCredentialMapper credentialMapper,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            BaseUrlPolicy baseUrlPolicy) {
        this(credentialMapper, credentialCipher, objectMapper, baseUrlPolicy, new AiClientProperties());
    }

    AiCredentialServiceImpl(
            AiProviderCredentialMapper credentialMapper,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            BaseUrlPolicy baseUrlPolicy,
            AiClientProperties systemProperties) {
        this.credentialMapper = credentialMapper;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.baseUrlPolicy = baseUrlPolicy;
        this.systemProperties = systemProperties;
    }

    @Override
    public AiCredentialVO get(Long userId) {
        AiProviderCredential credential = find(userId);
        return credential == null ? unconfigured() : toVo(credential);
    }

    @Override
    @Transactional
    public AiCredentialVO saveOrReplace(Long userId, AiCredentialUpsertRequestDTO request) {
        if (!credentialCipher.isEnabled()) {
            throw new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "AI Credential 加密配置不可用");
        }
        ValidatedInput input = validateInput(request);
        AiProviderCredential existing = find(userId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            AiProviderCredential created = new AiProviderCredential();
            created.setUserId(userId);
            created.setProviderType(PROVIDER_TYPE);
            created.setBaseUrl(input.baseUrl());
            // It exists only inside this transaction until encryption succeeds and is replaced.
            created.setEncryptedApiKey("pending");
            created.setEncryptionKeyVersion("pending");
            created.setModel(input.model());
            created.setConfigJson(input.configJson());
            created.setStatus(DISABLED);
            created.setCredentialRevision(1L);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            credentialMapper.insert(created);
            CredentialCipher.EncryptedValue encrypted = credentialCipher.encrypt(request.getApiKey(), userId, created.getId());
            created.setEncryptedApiKey(encrypted.envelope());
            created.setEncryptionKeyVersion(encrypted.keyVersion());
            credentialMapper.updateById(created);
            return toVo(created);
        }

        CredentialCipher.EncryptedValue encrypted = credentialCipher.encrypt(request.getApiKey(), userId, existing.getId());
        existing.setBaseUrl(input.baseUrl());
        existing.setEncryptedApiKey(encrypted.envelope());
        existing.setEncryptionKeyVersion(encrypted.keyVersion());
        existing.setModel(input.model());
        existing.setConfigJson(input.configJson());
        existing.setStatus(DISABLED);
        existing.setCredentialRevision(existing.getCredentialRevision() + 1L);
        existing.setUpdatedAt(now);
        int updated = credentialMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AiProviderCredential>()
                .eq("id", existing.getId())
                .eq("user_id", userId)
                .eq("credential_revision", existing.getCredentialRevision() - 1L)
                .set("base_url", existing.getBaseUrl())
                .set("encrypted_api_key", existing.getEncryptedApiKey())
                .set("encryption_key_version", existing.getEncryptionKeyVersion())
                .set("model", existing.getModel())
                .set("config_json", existing.getConfigJson())
                .set("status", existing.getStatus())
                .set("credential_revision", existing.getCredentialRevision())
                .set("updated_at", existing.getUpdatedAt()));
        if (updated != 1) {
            throw credentialChanged();
        }
        return toVo(existing);
    }

    @Override
    @Transactional
    public AiCredentialVO enable(Long userId) {
        AiProviderCredential credential = require(userId);
        credentialCipher.validateEnvelopeKeyVersion(
                credential.getEncryptedApiKey(), credential.getEncryptionKeyVersion());
        credentialCipher.decrypt(credential.getEncryptedApiKey(), userId, credential.getId());
        rotateIfNeeded(credential, userId);
        updateStatus(credential, userId, ACTIVE);
        return toVo(credential);
    }

    @Override
    @Transactional
    public AiCredentialVO disable(Long userId) {
        AiProviderCredential credential = require(userId);
        updateStatus(credential, userId, DISABLED);
        return toVo(credential);
    }

    @Override
    @Transactional
    public void delete(Long userId) {
        AiProviderCredential credential = find(userId);
        if (credential != null) {
            credentialMapper.deleteById(credential.getId());
        }
    }

    @Override
    public Optional<AiSelectionSnapshot> resolveCurrentSelection(Long userId) {
        if (!credentialCipher.isEnabled()) {
            return Optional.empty();
        }
        AiProviderCredential credential = find(userId);
        if (credential == null || !ACTIVE.equals(credential.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(toSelection(credential));
    }

    @Override
    @Transactional
    public DecryptedCredentialMaterial resolveMaterial(Long userId, AiSelectionSnapshot selection) {
        if (selection == null || !selection.isUserByok() || selection.credentialId() == null
                || selection.credentialRevision() == null) {
            throw credentialChanged();
        }
        AiProviderCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<AiProviderCredential>()
                .eq(AiProviderCredential::getId, selection.credentialId())
                .eq(AiProviderCredential::getUserId, userId)
                .eq(AiProviderCredential::getProviderType, PROVIDER_TYPE));
        if (credential == null
                || !ACTIVE.equals(credential.getStatus())
                || !selection.credentialRevision().equals(credential.getCredentialRevision())
                || !selection.baseUrl().equals(credential.getBaseUrl())
                || !selection.model().equals(credential.getModel())
                || !selection.configJson().equals(credential.getConfigJson())) {
            throw credentialChanged();
        }
        credentialCipher.validateEnvelopeKeyVersion(
                credential.getEncryptedApiKey(), credential.getEncryptionKeyVersion());
        String apiKey = credentialCipher.decrypt(credential.getEncryptedApiKey(), userId, credential.getId());
        rotateIfNeeded(credential, userId);
        return new DecryptedCredentialMaterial(
                apiKey,
                credential.getBaseUrl(),
                credential.getModel(),
                credential.getConfigJson(),
                credential.getId(),
                credential.getCredentialRevision());
    }

    @Override
    public DecryptedCredentialMaterial candidateMaterial(Long userId, AiCredentialUpsertRequestDTO request) {
        ValidatedInput input = validateInput(request);
        return new DecryptedCredentialMaterial(
                request.getApiKey(),
                input.baseUrl(),
                input.model(),
                input.configJson(),
                null,
                null);
    }

    private void updateStatus(AiProviderCredential credential, Long userId, String status) {
        LocalDateTime now = LocalDateTime.now();
        int updated = credentialMapper.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AiProviderCredential>()
                        .eq("id", credential.getId())
                        .eq("user_id", userId)
                        .eq("credential_revision", credential.getCredentialRevision())
                        .set("status", status)
                        .set("updated_at", now));
        if (updated != 1) {
            throw credentialChanged();
        }
        credential.setStatus(status);
        credential.setUpdatedAt(now);
    }

    private void rotateIfNeeded(AiProviderCredential credential, Long userId) {
        if (!credentialCipher.needsRotation(credential.getEncryptionKeyVersion())) {
            return;
        }
        String previousEnvelope = credential.getEncryptedApiKey();
        CredentialCipher.EncryptedValue rotated = credentialCipher.rotate(
                previousEnvelope,
                userId,
                credential.getId());
        LocalDateTime now = LocalDateTime.now();
        int updated = credentialMapper.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AiProviderCredential>()
                        .eq("id", credential.getId())
                        .eq("user_id", userId)
                        .eq("credential_revision", credential.getCredentialRevision())
                        .eq("encrypted_api_key", previousEnvelope)
                        .set("encrypted_api_key", rotated.envelope())
                        .set("encryption_key_version", rotated.keyVersion())
                        .set("updated_at", now));
        if (updated != 1) {
            throw credentialChanged();
        }
        credential.setEncryptedApiKey(rotated.envelope());
        credential.setEncryptionKeyVersion(rotated.keyVersion());
        credential.setUpdatedAt(now);
    }

    private ValidatedInput validateInput(AiCredentialUpsertRequestDTO request) {
        if (request == null || request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new AiGatewayException(AiFailureCode.INVALID_CREDENTIAL, "API Key 不能为空");
        }
        String model = request.getModel() == null ? "" : request.getModel().strip();
        if (model.isBlank() || model.length() > 200 || containsControl(model)) {
            throw new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "模型名称不正确");
        }
        final URI normalizedBaseUrl;
        try {
            normalizedBaseUrl = baseUrlPolicy.validateStructure(request.getBaseUrl());
        } catch (OutboundTransportException exception) {
            if (exception.getKind() == OutboundTransportException.Kind.TIMEOUT) {
                throw new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider DNS 解析超时");
            }
            if (exception.getKind() == OutboundTransportException.Kind.INTERRUPTED) {
                throw new AiGatewayException(AiFailureCode.INTERRUPTED, "AI Provider DNS 解析被中断");
            }
            throw new AiGatewayException(AiFailureCode.UNSAFE_BASE_URL, "AI Provider Base URL 不安全");
        }
        String configJson = AiGenerationConfig.normalize(objectMapper, request.getConfig(), 0.2d, 16000);
        return new ValidatedInput(normalizedBaseUrl.toString(), model, configJson);
    }

    private boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private AiProviderCredential find(Long userId) {
        if (userId == null) {
            throw new AiGatewayException(AiFailureCode.INVALID_CREDENTIAL, "用户上下文不可用");
        }
        return credentialMapper.selectOne(new LambdaQueryWrapper<AiProviderCredential>()
                .eq(AiProviderCredential::getUserId, userId)
                .eq(AiProviderCredential::getProviderType, PROVIDER_TYPE));
    }

    private AiProviderCredential require(Long userId) {
        AiProviderCredential credential = find(userId);
        if (credential == null) {
            throw new AiGatewayException(AiFailureCode.INVALID_CREDENTIAL, "尚未配置 AI Provider");
        }
        return credential;
    }

    private AiSelectionSnapshot toSelection(AiProviderCredential credential) {
        return new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                PROVIDER_TYPE,
                credential.getId(),
                credential.getCredentialRevision(),
                credential.getBaseUrl(),
                credential.getModel(),
                credential.getConfigJson(),
                null);
    }

    private AiCredentialVO toVo(AiProviderCredential credential) {
        return AiCredentialVO.builder()
                .providerType(PROVIDER_TYPE)
                .baseUrl(credential.getBaseUrl())
                .model(credential.getModel())
                .config(readConfig(credential.getConfigJson()))
                .status(credential.getStatus())
                .configured(true)
                .apiKeyConfigured(true)
                .maskedApiKey("••••••••")
                .credentialStorageAvailable(credentialCipher.isEnabled())
                .credentialRevision(credential.getCredentialRevision())
                .createdAt(credential.getCreatedAt())
                .updatedAt(credential.getUpdatedAt())
                .build();
    }

    private AiCredentialVO unconfigured() {
        return AiCredentialVO.builder()
                .providerType(PROVIDER_TYPE)
                .status(DISABLED)
                .configured(false)
                .apiKeyConfigured(false)
                .maskedApiKey("")
                .config(Map.of())
                .credentialStorageAvailable(credentialCipher.isEnabled())
                .build();
    }

    private Map<String, Object> readConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception exception) {
            throw new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "已保存的 AI 配置不可用");
        }
    }

    private AiGatewayException credentialChanged() {
        return new AiGatewayException(AiFailureCode.CREDENTIAL_CHANGED, "AI Credential 已变更或不可用");
    }

    private record ValidatedInput(String baseUrl, String model, String configJson) {
    }
}
