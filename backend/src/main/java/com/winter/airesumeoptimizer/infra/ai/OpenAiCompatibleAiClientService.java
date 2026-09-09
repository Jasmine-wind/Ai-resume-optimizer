package com.winter.airesumeoptimizer.infra.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundRequest;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundResponse;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundTransportException;
import com.winter.airesumeoptimizer.infra.ai.transport.PinnedHttpTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * OpenAI-compatible provider adapter. It is the only component that knows the
 * endpoint, authorization scheme, response normalization and dialect recovery.
 *
 * <p>Dialect decisions are based only on bounded HTTP evidence. Model names,
 * provider brands and base URL brands are never protocol switches.</p>
 */
@Service
@Profile("!demo & !phase9-e2e")
public class OpenAiCompatibleAiClientService implements AiProviderAdapter {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int CACHE_CAPACITY = 256;
    private static final long CACHE_TTL_MILLIS = Duration.ofHours(12).toMillis();
    private static final int MAX_BASE_DIALECT_ATTEMPTS = 3;
    private static final int MAX_REASONING_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final PinnedHttpTransport transport;
    private final ConcurrentHashMap<String, CachedProfile> compatibilityCache = new ConcurrentHashMap<>();

    @Autowired
    public OpenAiCompatibleAiClientService(
            ObjectMapper objectMapper,
            PinnedHttpTransport transport) {
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    /** Retained for small JSON-focused unit tests. */
    OpenAiCompatibleAiClientService(ObjectMapper objectMapper) {
        this(objectMapper, new PinnedHttpTransport());
    }

    @Override
    public AiProviderResponse complete(AiProviderRequest request) {
        validateProviderRequest(request);
        long deadlineAt = System.nanoTime() + boundedTimeout(request.timeout()).toNanos();
        String cacheKey = cacheKey(request);
        if (request.compatibilityProfilePinned()) {
            return completeWithPinnedProfile(request, cacheKey, deadlineAt);
        }
        AiProviderCompatibilityProfile cached = getCachedProfile(cacheKey);
        if (cached != null) {
            int recoveryDispatches = 1;
            try {
                ParsedResponse parsed = dispatch(request.withCompatibilityProfile(cached), deadlineAt);
                if (parsed.hasFinalContent()) {
                    return parsed.toProviderResponse(1);
                }
                if (parsed.reasoningPresent()) {
                    invalidate(cacheKey, cached);
                } else {
                    throw missingFinalContent(1);
                }
            } catch (AiGatewayException failure) {
                if (!isNegotiableMismatch(failure)) {
                    throw failure;
                }
                recoveryDispatches = Math.max(1, failure.getProviderDispatchCount());
                invalidate(cacheKey, cached);
            }
            // A cached profile is allowed one bounded recovery through the
            // normal STANDARD negotiation state machine.
            return negotiateFromStandard(request, cacheKey, deadlineAt, recoveryDispatches);
        }
        return negotiateFromStandard(request, cacheKey, deadlineAt, 0);
    }

    private AiProviderResponse completeWithPinnedProfile(
            AiProviderRequest request,
            String cacheKey,
            long deadlineAt) {
        try {
            ParsedResponse parsed = dispatch(request.withCompatibilityProfile(request.compatibilityProfile()), deadlineAt);
            if (parsed.hasFinalContent()) {
                putCachedProfile(cacheKey, request.compatibilityProfile());
                return parsed.toProviderResponse(1);
            }
            if (!parsed.reasoningPresent()) {
                throw missingFinalContent(1);
            }
            return recoverReasoning(
                    request, request.compatibilityProfile(), cacheKey, deadlineAt, 1);
        } catch (AiGatewayException failure) {
            if (isNegotiableMismatch(failure)) {
                invalidate(cacheKey, request.compatibilityProfile());
                return negotiateFromStandard(request, cacheKey, deadlineAt,
                        Math.max(1, failure.getProviderDispatchCount()));
            }
            throw failure;
        }
    }

    String extractContent(String responseBody) {
        try {
            ParsedResponse parsed = parseResponse(responseBody);
            if (parsed.hasFinalContent()) {
                return parsed.finalContent();
            }
            throw missingFinalContentException();
        } catch (AiGatewayException exception) {
            throw new AiClientException(exception.getMessage());
        }
    }

    private AiProviderResponse negotiateFromStandard(
            AiProviderRequest request,
            String cacheKey,
            long deadlineAt,
            int initialDispatches) {
        AiProviderCompatibilityProfile profile = AiProviderCompatibilityProfile.standard();
        int dispatches = Math.max(0, initialDispatches);
        for (int baseAttempt = 0; baseAttempt < MAX_BASE_DIALECT_ATTEMPTS; baseAttempt++) {
            ParsedResponse parsed;
            try {
                parsed = dispatch(request.withCompatibilityProfile(profile), deadlineAt);
                dispatches++;
            } catch (AiGatewayException failure) {
                dispatches += Math.max(0, failure.getProviderDispatchCount());
                AiGatewayException total = failure.withProviderDispatchCount(dispatches);
                if (isUnsupportedBaseParameter(total, profile)) {
                    profile = nextBaseProfile(profile, total.getUnsupportedParameter());
                    continue;
                }
                throw total;
            }
            if (parsed.hasFinalContent()) {
                putCachedProfile(cacheKey, profile);
                return parsed.toProviderResponse(dispatches);
            }
            if (!parsed.reasoningPresent()) {
                throw missingFinalContent(dispatches);
            }
            return recoverReasoning(request, profile, cacheKey, deadlineAt, dispatches);
        }
        throw new AiGatewayException(
                AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE,
                "AI Provider 不支持可协商的 Chat Completions 参数",
                false,
                0L,
                dispatches,
                null);
    }

    private AiProviderResponse recoverReasoning(
            AiProviderRequest request,
            AiProviderCompatibilityProfile baseProfile,
            String cacheKey,
            long deadlineAt,
            int dispatches) {
        List<AiProviderCompatibilityProfile.ReasoningControl> controls = List.of(
                AiProviderCompatibilityProfile.ReasoningControl.THINKING_DISABLED,
                AiProviderCompatibilityProfile.ReasoningControl.ENABLE_THINKING_FALSE,
                AiProviderCompatibilityProfile.ReasoningControl.REASONING_EFFORT_LOW);
        int totalDispatches = dispatches;
        for (int attempt = 0; attempt < MAX_REASONING_ATTEMPTS; attempt++) {
            AiProviderCompatibilityProfile profile = baseProfile.withReasoningControl(controls.get(attempt));
            try {
                ParsedResponse parsed = dispatch(request.withCompatibilityProfile(profile), deadlineAt);
                totalDispatches++;
                if (parsed.hasFinalContent()) {
                    putCachedProfile(cacheKey, profile);
                    return parsed.toProviderResponse(totalDispatches);
                }
                if (!parsed.reasoningPresent()) {
                    throw missingFinalContent(0);
                }
                // The control was accepted, but the provider still returned
                // reasoning without an answer. Continue to the next bounded mode.
            } catch (AiGatewayException failure) {
                totalDispatches += Math.max(0, failure.getProviderDispatchCount());
                AiGatewayException total = failure.withProviderDispatchCount(totalDispatches);
                if (isUnsupportedReasoningParameter(total)) {
                    continue;
                }
                throw total;
            }
        }
        throw new AiGatewayException(
                AiFailureCode.FINAL_CONTENT_MISSING,
                "当前模型没有返回可用的最终文本，系统已尝试兼容模式。请在 AI 设置中重新测试当前接口，或更换能够返回最终结果的模型。",
                false,
                0L,
                totalDispatches,
                null);
    }

    private ParsedResponse dispatch(AiProviderRequest request, long deadlineAt) {
        Duration remaining = remainingTimeout(deadlineAt);
        AiProviderRequest timedRequest = request.withTimeout(remaining);
        String payload = serializeRequest(timedRequest);
        try {
            OutboundResponse response = transport.execute(new OutboundRequest(
                    "POST",
                    timedRequest.baseUrl(),
                    CHAT_COMPLETIONS_PATH,
                    Map.of(
                            "Authorization", "Bearer " + timedRequest.apiKey(),
                            "Content-Type", "application/json",
                            "Accept", "application/json"),
                    payload,
                    timedRequest.timeout()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw mapHttpFailure(response);
            }
            try {
                return parseResponse(response.body());
            } catch (AiGatewayException exception) {
                throw exception.withDispatchCount(1);
            }
        } catch (OutboundTransportException exception) {
            throw mapTransportFailure(exception).withRetryProfile(request.compatibilityProfile());
        } catch (AiGatewayException exception) {
            throw exception.withRetryProfile(request.compatibilityProfile());
        }
    }

    private AiGatewayException missingFinalContent(int dispatches) {
        return new AiGatewayException(
                AiFailureCode.FINAL_CONTENT_MISSING,
                "当前模型没有返回可用的最终文本，系统已尝试兼容模式。请在 AI 设置中重新测试当前接口，或更换能够返回最终结果的模型。",
                false,
                0L,
                dispatches,
                null);
    }

    private AiGatewayException missingFinalContentException() {
        return new AiGatewayException(
                AiFailureCode.FINAL_CONTENT_MISSING,
                "当前模型没有返回可用的最终文本，系统已尝试兼容模式。请在 AI 设置中重新测试当前接口，或更换能够返回最终结果的模型。");
    }

    private ParsedResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!root.isObject() || !choices.isArray() || choices.isEmpty()) {
                throw new AiGatewayException(
                        AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE,
                        "AI Provider 返回的 Chat Completions 响应格式不受支持");
            }
            JsonNode choiceNode = choices.path(0);
            JsonNode messageNode = choiceNode.path("message");
            String content = readMessageContent(messageNode);
            if (content.isBlank() && choiceNode.path("text").isTextual()) {
                content = choiceNode.path("text").asText();
            }
            boolean reasoningPresent = hasValue(messageNode.path("reasoning_content"));
            if (content.isBlank() && !messageNode.path("refusal").asText("").isBlank()) {
                throw new AiGatewayException(AiFailureCode.REFUSAL, "AI Provider 拒绝生成此内容");
            }
            JsonNode usage = root.path("usage");
            return new ParsedResponse(
                    content,
                    reasoningPresent,
                    positiveLong(usage.path("prompt_tokens")),
                    positiveLong(usage.path("completion_tokens")));
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiGatewayException(
                    AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE,
                    "AI Provider 返回的 Chat Completions 响应格式不受支持");
        }
    }

    private String serializeRequest(AiProviderRequest request) {
        try {
            List<Map<String, String>> messages = request.messages().stream()
                    .map(message -> Map.of(
                            "role", message.role().name().toLowerCase(),
                            "content", message.content()))
                    .toList();
            AiProviderCompatibilityProfile profile = request.compatibilityProfile();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", request.model());
            if (profile.temperatureMode() == AiProviderCompatibilityProfile.TemperatureMode.INCLUDE) {
                payload.put("temperature", request.temperature());
            }
            if (profile.tokenParameter() == AiProviderCompatibilityProfile.TokenParameter.MAX_TOKENS) {
                payload.put("max_tokens", request.maxTokens());
            } else if (profile.tokenParameter()
                    == AiProviderCompatibilityProfile.TokenParameter.MAX_COMPLETION_TOKENS) {
                payload.put("max_completion_tokens", request.maxTokens());
            }
            addReasoningControl(payload, profile.reasoningControl());
            payload.put("messages", messages);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "AI 请求无法序列化");
        }
    }

    private void addReasoningControl(
            Map<String, Object> payload,
            AiProviderCompatibilityProfile.ReasoningControl control) {
        switch (control) {
            case THINKING_DISABLED -> payload.put("thinking", Map.of("type", "disabled"));
            case ENABLE_THINKING_FALSE -> payload.put("enable_thinking", false);
            case REASONING_EFFORT_LOW -> payload.put("reasoning_effort", "low");
            case NONE -> {
                // STANDARD deliberately sends no reasoning-specific field.
            }
        }
    }

    private AiGatewayException mapHttpFailure(OutboundResponse response) {
        int status = response.statusCode();
        if (status == 401) {
            return new AiGatewayException(AiFailureCode.INVALID_CREDENTIAL, "AI Provider API Key 无效", false, 0L, 1, null);
        }
        if (status == 403) {
            return new AiGatewayException(AiFailureCode.PROVIDER_UNAUTHORIZED, "AI Provider 没有调用权限", false, 0L, 1, null);
        }
        if (status == 404) {
            return new AiGatewayException(AiFailureCode.MODEL_NOT_FOUND, "AI Provider 未找到指定模型或端点", false, 0L, 1, null);
        }
        if (status == 429) {
            return new AiGatewayException(
                    AiFailureCode.RATE_LIMITED,
                    "AI Provider 请求过于频繁",
                    true,
                    retryAfterMillis(response.headers()),
                    1,
                    null);
        }
        if (status == 408) {
            return new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider 请求超时", true, 0L, 1, null);
        }
        if (status == 502 || status == 503 || status == 504) {
            return new AiGatewayException(AiFailureCode.PROVIDER_UNAVAILABLE, "AI Provider 暂时不可用", true, 0L, 1, null);
        }
        if (status >= 500) {
            return new AiGatewayException(AiFailureCode.PROVIDER_UNAVAILABLE, "AI Provider 暂时不可用", false, 0L, 1, null);
        }
        if (status == 400 || status == 422) {
            AiUnsupportedParameter parameter = unsupportedParameter(response.body());
            if (parameter != null) {
                return new AiGatewayException(
                        AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE,
                        "AI Provider 不支持当前 Chat Completions 参数",
                        false,
                        0L,
                        1,
                        parameter);
            }
            return new AiGatewayException(
                    AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE,
                    "AI Provider 拒绝了不明确的 Chat Completions 请求",
                    false,
                    0L,
                    1,
                    null);
        }
        return new AiGatewayException(
                AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE,
                "AI Provider 返回了不受支持的 Chat Completions 响应",
                false,
                0L,
                1,
                null);
    }

    private AiUnsupportedParameter unsupportedParameter(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode error = root.path("error");
            String param = scalarText(error.path("param"));
            String code = scalarText(error.path("code"));
            String type = scalarText(error.path("type"));
            String message = scalarText(error.path("message"));
            String evidence = (code + " " + type + " " + message).toLowerCase(java.util.Locale.ROOT);
            if (!containsUnsupportedSignal(evidence)) {
                return null;
            }
            AiUnsupportedParameter fromParam = toUnsupportedParameter(param);
            if (fromParam != null) {
                return fromParam;
            }
            for (AiUnsupportedParameter candidate : AiUnsupportedParameter.values()) {
                if (containsParameter(evidence, candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // Provider bodies are untrusted and are intentionally discarded.
        }
        return null;
    }

    private boolean containsUnsupportedSignal(String evidence) {
        return evidence.contains("unsupported")
                || evidence.contains("not supported")
                || evidence.contains("does not support")
                || evidence.contains("not allowed")
                || evidence.contains("not_support")
                || evidence.contains("unknown parameter")
                || evidence.contains("unknown field")
                || evidence.contains("unrecognized parameter")
                || evidence.contains("extra_forbidden")
                || evidence.contains("extra inputs");
    }

    private boolean containsParameter(String evidence, AiUnsupportedParameter parameter) {
        String token = switch (parameter) {
            case TEMPERATURE -> "temperature";
            case MAX_TOKENS -> "max_tokens";
            case MAX_COMPLETION_TOKENS -> "max_completion_tokens";
            case THINKING -> "thinking";
            case ENABLE_THINKING -> "enable_thinking";
            case REASONING_EFFORT -> "reasoning_effort";
        };
        return evidence.contains(token);
    }

    private AiUnsupportedParameter toUnsupportedParameter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "temperature" -> AiUnsupportedParameter.TEMPERATURE;
            case "max_tokens" -> AiUnsupportedParameter.MAX_TOKENS;
            case "max_completion_tokens" -> AiUnsupportedParameter.MAX_COMPLETION_TOKENS;
            case "thinking" -> AiUnsupportedParameter.THINKING;
            case "enable_thinking" -> AiUnsupportedParameter.ENABLE_THINKING;
            case "reasoning_effort" -> AiUnsupportedParameter.REASONING_EFFORT;
            default -> null;
        };
    }

    private boolean isNegotiableMismatch(AiGatewayException failure) {
        return failure.getFailureCode() == AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE
                && failure.getUnsupportedParameter() != null;
    }

    private boolean isUnsupportedBaseParameter(
            AiGatewayException failure,
            AiProviderCompatibilityProfile profile) {
        AiUnsupportedParameter parameter = failure.getUnsupportedParameter();
        if (parameter == null) {
            return false;
        }
        return switch (parameter) {
            case TEMPERATURE -> profile.temperatureMode()
                    == AiProviderCompatibilityProfile.TemperatureMode.INCLUDE;
            case MAX_TOKENS -> profile.tokenParameter()
                    == AiProviderCompatibilityProfile.TokenParameter.MAX_TOKENS;
            case MAX_COMPLETION_TOKENS -> profile.tokenParameter()
                    == AiProviderCompatibilityProfile.TokenParameter.MAX_COMPLETION_TOKENS;
            default -> false;
        };
    }

    private boolean isUnsupportedReasoningParameter(AiGatewayException failure) {
        AiUnsupportedParameter parameter = failure.getUnsupportedParameter();
        return failure.getFailureCode() == AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE
                && (parameter == AiUnsupportedParameter.THINKING
                || parameter == AiUnsupportedParameter.ENABLE_THINKING
                || parameter == AiUnsupportedParameter.REASONING_EFFORT);
    }

    private AiProviderCompatibilityProfile nextBaseProfile(
            AiProviderCompatibilityProfile current,
            AiUnsupportedParameter unsupported) {
        return switch (unsupported) {
            case TEMPERATURE -> current.withTemperatureMode(
                    AiProviderCompatibilityProfile.TemperatureMode.OMIT);
            case MAX_TOKENS -> current.withTokenParameter(
                    AiProviderCompatibilityProfile.TokenParameter.MAX_COMPLETION_TOKENS);
            case MAX_COMPLETION_TOKENS -> current.withTokenParameter(
                    AiProviderCompatibilityProfile.TokenParameter.OMIT);
            default -> current;
        };
    }

    private String readMessageContent(JsonNode messageNode) {
        JsonNode contentNode = messageNode.path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isObject()) {
            JsonNode textNode = contentNode.path("text");
            if (textNode.isTextual()) {
                return textNode.asText();
            }
            JsonNode valueNode = contentNode.path("value");
            if (valueNode.isTextual()) {
                return valueNode.asText();
            }
        }
        if (contentNode.isArray()) {
            StringBuilder result = new StringBuilder();
            for (JsonNode item : contentNode) {
                JsonNode textNode = item.path("text");
                if (!textNode.isTextual()) {
                    textNode = item.path("content");
                }
                if (textNode.isTextual() && !textNode.asText().isBlank()) {
                    if (!result.isEmpty()) {
                        result.append('\n');
                    }
                    result.append(textNode.asText());
                }
            }
            return result.toString();
        }
        JsonNode legacyTextNode = messageNode.path("text");
        return legacyTextNode.isTextual() ? legacyTextNode.asText() : "";
    }

    private boolean hasValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        return node.isTextual() ? !node.asText().isBlank() : !node.isEmpty();
    }

    private Long positiveLong(JsonNode value) {
        return value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null;
    }

    private String scalarText(JsonNode value) {
        return value != null && value.isValueNode() ? value.asText("") : "";
    }

    private long retryAfterMillis(Map<String, String> headers) {
        String value = headers.entrySet().stream()
                .filter(entry -> "retry-after".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        try {
            return Math.min(2000L, Math.max(0L, Math.multiplyExact(Long.parseLong(value.strip()), 1000L)));
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private List<AiChatMessage> validateMessages(List<AiChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI 输入不能为空");
        }
        for (AiChatMessage message : messages) {
            if (message == null || message.role() == null || message.content() == null || message.content().isBlank()) {
                throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI 输入不能为空");
            }
        }
        return List.copyOf(messages);
    }

    private void validateProviderRequest(AiProviderRequest request) {
        if (request == null || request.apiKey() == null || request.apiKey().isBlank()) {
            throw new AiGatewayException(AiFailureCode.INVALID_CREDENTIAL, "AI API Key 未配置");
        }
        if (request.baseUrl() == null || request.baseUrl().isBlank()
                || request.model() == null || request.model().isBlank()) {
            throw new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "AI Provider 配置不完整");
        }
        validateMessages(request.messages());
    }

    private String cacheKey(AiProviderRequest request) {
        return normalizeBaseUrl(request.baseUrl()) + "\u0000" + request.model().strip()
                + "\u0000" + sha256(request.apiKey());
    }

    private String normalizeBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort() < 0 ? 443 : uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/") && !path.isEmpty()) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + ":" + port + path;
        } catch (RuntimeException exception) {
            return baseUrl.strip();
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private AiProviderCompatibilityProfile getCachedProfile(String key) {
        CachedProfile cached = compatibilityCache.get(key);
        if (cached == null) {
            return null;
        }
        if (System.currentTimeMillis() - cached.createdAt() >= CACHE_TTL_MILLIS) {
            compatibilityCache.remove(key, cached);
            return null;
        }
        return cached.profile();
    }

    private void putCachedProfile(String key, AiProviderCompatibilityProfile profile) {
        synchronized (compatibilityCache) {
            long now = System.currentTimeMillis();
            compatibilityCache.entrySet().removeIf(entry -> now - entry.getValue().createdAt() >= CACHE_TTL_MILLIS);
            if (compatibilityCache.size() >= CACHE_CAPACITY && !compatibilityCache.containsKey(key)) {
                compatibilityCache.entrySet().stream()
                        .min(Map.Entry.comparingByValue(java.util.Comparator.comparingLong(CachedProfile::createdAt)))
                        .ifPresent(entry -> compatibilityCache.remove(entry.getKey(), entry.getValue()));
            }
            compatibilityCache.put(key, new CachedProfile(profile, now));
        }
    }

    private void invalidate(String key, AiProviderCompatibilityProfile profile) {
        compatibilityCache.computeIfPresent(key, (ignored, cached) -> cached.profile().equals(profile) ? null : cached);
    }

    private record CachedProfile(AiProviderCompatibilityProfile profile, long createdAt) {
    }

    private record ParsedResponse(
            String finalContent,
            boolean reasoningPresent,
            Long inputTokens,
            Long outputTokens) {

        boolean hasFinalContent() {
            return finalContent != null && !finalContent.isBlank();
        }

        AiProviderResponse toProviderResponse(int dispatchCount) {
            return new AiProviderResponse(finalContent, inputTokens, outputTokens, dispatchCount);
        }
    }

    private Duration boundedTimeout(Duration requested) {
        Duration value = requested == null || requested.isZero() || requested.isNegative()
                ? Duration.ofSeconds(30)
                : requested;
        return value.compareTo(PinnedHttpTransport.MAX_TOTAL_TIMEOUT) > 0
                ? PinnedHttpTransport.MAX_TOTAL_TIMEOUT
                : value;
    }

    private Duration remainingTimeout(long deadlineAt) {
        long nanos = deadlineAt - System.nanoTime();
        if (nanos <= 0) {
            throw new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider 请求超时");
        }
        return Duration.ofNanos(nanos);
    }

    private AiGatewayException mapTransportFailure(OutboundTransportException exception) {
        return switch (exception.getKind()) {
            case UNSAFE_URL -> new AiGatewayException(AiFailureCode.UNSAFE_BASE_URL, "AI Provider Base URL 不安全", false, 0L, 1, null);
            case TIMEOUT -> new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider 请求超时", true, 0L, 1, null);
            case RESPONSE_TOO_LARGE -> new AiGatewayException(AiFailureCode.RESPONSE_TOO_LARGE, "AI Provider 响应过大", false, 0L, 1, null);
            case NETWORK -> new AiGatewayException(AiFailureCode.PROVIDER_UNAVAILABLE, "AI Provider 网络不可用", true, 0L, 1, null);
            case INTERRUPTED -> new AiGatewayException(AiFailureCode.INTERRUPTED, "AI Provider 请求被中断", false, 0L, 1, null);
        };
    }
}
