package com.winter.airesumeoptimizer.infra.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundRequest;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundResponse;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundTransportException;
import com.winter.airesumeoptimizer.infra.ai.transport.PinnedHttpTransport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * OpenAI-compatible provider adapter. It is the only component that knows the
 * endpoint, authorization scheme and provider response shape.
 *
 * <p>Business modules can only reach this adapter through the context-aware Gateway.</p>
 */
@Service
@Profile("!demo & !phase9-e2e")
public class OpenAiCompatibleAiClientService implements AiProviderAdapter {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final ObjectMapper objectMapper;
    private final PinnedHttpTransport transport;

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
        String payload = serializeRequest(request);
        try {
            OutboundResponse response = transport.execute(new OutboundRequest(
                    "POST",
                    request.baseUrl(),
                    CHAT_COMPLETIONS_PATH,
                    Map.of(
                            "Authorization", "Bearer " + request.apiKey(),
                            "Content-Type", "application/json",
                            "Accept", "application/json"),
                    payload,
                    request.timeout()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw mapHttpFailure(response);
            }
            return parseResponse(response.body());
        } catch (OutboundTransportException exception) {
            throw mapTransportFailure(exception);
        }
    }

    String extractContent(String responseBody) {
        try {
            return parseResponse(responseBody).text();
        } catch (AiGatewayException exception) {
            throw new AiClientException(exception.getMessage());
        }
    }

    private AiProviderResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choiceNode = root.path("choices").path(0);
            JsonNode messageNode = choiceNode.path("message");
            String content = readMessageContent(messageNode);
            // A few gateways return a legacy completion-shaped choice even on the
            // chat endpoint. It is still validated by the downstream JSON/parser
            // contracts, so accepting this text does not make provider data trusted.
            if (content.isBlank() && choiceNode.path("text").isTextual()) {
                content = choiceNode.path("text").asText();
            }
            if (content.isBlank()) {
                if (!messageNode.path("refusal").asText("").isBlank()) {
                    throw new AiGatewayException(AiFailureCode.REFUSAL, "AI Provider 拒绝生成此内容");
                }
                if (!messageNode.path("reasoning_content").asText("").isBlank()) {
                    throw new AiGatewayException(
                            AiFailureCode.SCHEMA_INVALID,
                            "AI Provider 只返回了推理内容，没有返回最终文本；请改用支持 Chat Completions 文本输出的模型");
                }
                if ("length".equals(choiceNode.path("finish_reason").asText(""))) {
                    throw new AiGatewayException(
                            AiFailureCode.SCHEMA_INVALID,
                            "AI 响应中缺少文本内容，可能是 max_tokens 不足，请调大 AI_MAX_TOKENS 后重试");
                }
                throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI Provider 响应缺少文本内容");
            }
            JsonNode usage = root.path("usage");
            return new AiProviderResponse(
                    content,
                    positiveLong(usage.path("prompt_tokens")),
                    positiveLong(usage.path("completion_tokens")));
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI Provider 响应格式不正确");
        }
    }

    private String serializeRequest(AiProviderRequest request) {
        try {
            List<Map<String, String>> messages = request.messages().stream()
                    .map(message -> Map.of(
                            "role", message.role().name().toLowerCase(),
                            "content", message.content()))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", request.model());
            payload.put("temperature", request.temperature());
            payload.put("max_tokens", request.maxTokens());
            payload.put("messages", messages);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new AiGatewayException(AiFailureCode.CONFIGURATION_INVALID, "AI 请求无法序列化");
        }
    }

    private AiGatewayException mapHttpFailure(OutboundResponse response) {
        int status = response.statusCode();
        if (status == 401) {
            return new AiGatewayException(AiFailureCode.INVALID_CREDENTIAL, "AI Provider API Key 无效");
        }
        if (status == 403) {
            return new AiGatewayException(AiFailureCode.PROVIDER_UNAUTHORIZED, "AI Provider 没有调用权限");
        }
        if (status == 404) {
            return new AiGatewayException(AiFailureCode.MODEL_NOT_FOUND, "AI Provider 未找到指定模型或端点");
        }
        if (status == 429) {
            return new AiGatewayException(
                    AiFailureCode.RATE_LIMITED,
                    "AI Provider 请求过于频繁",
                    true,
                    retryAfterMillis(response.headers()));
        }
        if (status == 408) {
            return new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider 请求超时", true);
        }
        if (status == 502 || status == 503 || status == 504) {
            return new AiGatewayException(AiFailureCode.PROVIDER_UNAVAILABLE, "AI Provider 暂时不可用", true);
        }
        if (status >= 500) {
            return new AiGatewayException(AiFailureCode.PROVIDER_UNAVAILABLE, "AI Provider 暂时不可用");
        }
        return new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI Provider 拒绝了请求");
    }

    private AiGatewayException mapTransportFailure(OutboundTransportException exception) {
        return switch (exception.getKind()) {
            case UNSAFE_URL -> new AiGatewayException(AiFailureCode.UNSAFE_BASE_URL, "AI Provider Base URL 不安全");
            case TIMEOUT -> new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider 请求超时", true);
            case RESPONSE_TOO_LARGE -> new AiGatewayException(AiFailureCode.RESPONSE_TOO_LARGE, "AI Provider 响应过大");
            case NETWORK -> new AiGatewayException(AiFailureCode.PROVIDER_UNAVAILABLE, "AI Provider 网络不可用", true);
            case INTERRUPTED -> new AiGatewayException(AiFailureCode.INTERRUPTED, "AI Provider 请求被中断");
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

    private Long positiveLong(JsonNode value) {
        return value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null;
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

}
