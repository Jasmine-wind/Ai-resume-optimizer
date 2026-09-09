package com.winter.airesumeoptimizer.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundRequest;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundResponse;
import com.winter.airesumeoptimizer.infra.ai.transport.PinnedHttpTransport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenAiCompatibleAiClientServiceTest {

    private static final String FINAL_MISSING_MESSAGE =
            "当前模型没有返回可用的最终文本，系统已尝试兼容模式。请在 AI 设置中重新测试当前接口，或更换能够返回最终结果的模型。";

    @Test
    void extractContentShouldNormalizeSupportedChatCompletionShapes() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThat(service.extractContent("""
                {"choices":[{"message":{"content":"字符串"}}]}
                """)).isEqualTo("字符串");
        assertThat(service.extractContent("""
                {"choices":[{"message":{"content":{"text":"对象文本"}}}]}
                """)).isEqualTo("对象文本");
        assertThat(service.extractContent("""
                {"choices":[{"message":{"content":{"value":"对象值"}}}]}
                """)).isEqualTo("对象值");
        assertThat(service.extractContent("""
                {"choices":[{"message":{"content":[{"text":"第一段"},{"content":"第二段"}]}}]}
                """)).isEqualTo("第一段\n第二段");
        assertThat(service.extractContent("""
                {"choices":[{"message":{"text":"消息文本"}}]}
                """)).isEqualTo("消息文本");
        assertThat(service.extractContent("""
                {"choices":[{"text":"选择文本","message":{}}]}
                """)).isEqualTo("选择文本");
    }

    @Test
    void extractContentShouldAlwaysPreferFinalContentOverReasoning() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThat(service.extractContent("""
                {"choices":[{"message":{"reasoning_content":"private reasoning","content":"最终答案"}}]}
                """)).isEqualTo("最终答案");
    }

    @Test
    void extractContentShouldUseProviderNeutralFinalContentMissingMessage() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThatThrownBy(() -> service.extractContent("""
                {"choices":[{"finish_reason":"length","message":{"content":null,"reasoning_content":"private reasoning"}}]}
                """))
                .isInstanceOf(AiClientException.class)
                .hasMessage(FINAL_MISSING_MESSAGE)
                .hasMessageNotContaining("private reasoning");
    }

    @Test
    void completeShouldUseStandardProfileInOneDispatchWithoutModelNameBranching() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = successfulTransport("{}", "test-key");
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        AiProviderResponse response = service.complete(providerRequest("test-key", "arbitrary-model"));

        assertThat(response.dispatchCount()).isEqualTo(1);
        JsonNode body = capturedBodies(transport, objectMapper).get(0);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2d);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(100);
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("enable_thinking")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void completeShouldNegotiateMaxCompletionTokensOnlyOnExplicitUnsupportedEvidence() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(unsupported("max_tokens"))
                .thenReturn(success("{\"ok\":true}"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        AiProviderResponse response = service.complete(providerRequest("test-key", "same-behavior-model"));

        assertThat(response.dispatchCount()).isEqualTo(2);
        List<JsonNode> bodies = capturedBodies(transport, objectMapper);
        assertThat(bodies.get(0).has("max_tokens")).isTrue();
        assertThat(bodies.get(1).has("max_tokens")).isFalse();
        assertThat(bodies.get(1).path("max_completion_tokens").asInt()).isEqualTo(100);
    }

    @Test
    void completeShouldOmitTemperatureOnlyOnExplicitUnsupportedEvidence() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(unsupported("temperature"))
                .thenReturn(success("{}"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        assertThat(service.complete(providerRequest("key-temperature", "model-a")).dispatchCount()).isEqualTo(2);

        List<JsonNode> bodies = capturedBodies(transport, objectMapper);
        assertThat(bodies.get(0).has("temperature")).isTrue();
        assertThat(bodies.get(1).has("temperature")).isFalse();
        assertThat(bodies.get(1).has("max_tokens")).isTrue();
    }

    @Test
    void completeShouldOmitTokenParameterOnlyAfterBothTokenNamesAreExplicitlyRejected() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(unsupported("max_tokens"))
                .thenReturn(unsupported("max_completion_tokens"))
                .thenReturn(success("{}"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        assertThat(service.complete(providerRequest("key-token-omit", "model-b")).dispatchCount()).isEqualTo(3);

        List<JsonNode> bodies = capturedBodies(transport, objectMapper);
        assertThat(bodies.get(0).has("max_tokens")).isTrue();
        assertThat(bodies.get(1).has("max_completion_tokens")).isTrue();
        assertThat(bodies.get(2).has("max_tokens")).isFalse();
        assertThat(bodies.get(2).has("max_completion_tokens")).isFalse();
    }

    @Test
    void completeShouldNotRetryUnknown400OrExposeRawProviderBody() {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class))).thenReturn(new OutboundResponse(
                400,
                Map.of(),
                "{\"error\":{\"message\":\"resume private text and secret-provider-detail\",\"param\":\"custom\"}}"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper(), transport);

        assertThatThrownBy(() -> service.complete(providerRequest("key-unknown", "model-c")))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(failure -> {
                    AiGatewayException exception = (AiGatewayException) failure;
                    assertThat(exception.getFailureCode()).isEqualTo(AiFailureCode.PROVIDER_PROTOCOL_INCOMPATIBLE);
                    assertThat(exception.getDispatchCount()).isEqualTo(1);
                    assertThat(exception.getMessage())
                            .doesNotContain("resume private text")
                            .doesNotContain("secret-provider-detail");
                });
        verify(transport, times(1)).execute(any());
    }

    @Test
    void completeShouldRecoverReasoningOnlyWithoutUsingReasoningAsFinalContent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(reasoningOnly("private first reasoning"))
                .thenReturn(unsupported("thinking"))
                .thenReturn(success("{\"result\":true}"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        AiProviderResponse response = service.complete(providerRequest("key-reasoning", "model-d"));

        assertThat(response.text()).isEqualTo("{\"result\":true}");
        assertThat(response.text()).doesNotContain("private first reasoning");
        assertThat(response.dispatchCount()).isEqualTo(3);
        List<JsonNode> bodies = capturedBodies(transport, objectMapper);
        assertThat(bodies.get(0).has("thinking")).isFalse();
        assertThat(bodies.get(1).path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(bodies.get(2).path("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void completeShouldStopAfterThreeReasoningControlsAndUseFinalContentMissing() {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class))).thenReturn(
                reasoningOnly("r1"), reasoningOnly("r2"), reasoningOnly("r3"), reasoningOnly("r4"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper(), transport);

        assertThatThrownBy(() -> service.complete(providerRequest("key-bounded", "model-e")))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(failure -> {
                    AiGatewayException exception = (AiGatewayException) failure;
                    assertThat(exception.getFailureCode()).isEqualTo(AiFailureCode.FINAL_CONTENT_MISSING);
                    assertThat(exception.getDispatchCount()).isEqualTo(4);
                    assertThat(exception.getMessage()).isEqualTo(FINAL_MISSING_MESSAGE);
                });
        verify(transport, times(4)).execute(any());
    }

    @Test
    void completeShouldCacheOnlySuccessfulProfilePerCredentialFingerprint() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(unsupported("max_tokens"))
                .thenReturn(success("first"))
                .thenReturn(success("second"))
                .thenReturn(success("different-key"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        assertThat(service.complete(providerRequest("key-cache-a", "model-f")).dispatchCount()).isEqualTo(2);
        assertThat(service.complete(providerRequest("key-cache-a", "model-f")).dispatchCount()).isEqualTo(1);
        assertThat(service.complete(providerRequest("key-cache-b", "model-f")).dispatchCount()).isEqualTo(1);

        List<JsonNode> bodies = capturedBodies(transport, objectMapper);
        assertThat(bodies.get(1).has("max_completion_tokens")).isTrue();
        assertThat(bodies.get(2).has("max_completion_tokens")).isTrue();
        assertThat(bodies.get(3).has("max_tokens")).isTrue();
    }

    @Test
    void cachedProfileRecoveryIsBoundedToOneCachedDispatchPlusThreeBaseAndThreeReasoningDispatches() {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(reasoningOnly("warm-reasoning"))
                .thenReturn(success("warm-final"))
                .thenReturn(unsupported("thinking"))
                .thenReturn(unsupported("max_tokens"))
                .thenReturn(unsupported("temperature"))
                .thenReturn(reasoningOnly("base-reasoning"))
                .thenReturn(reasoningOnly("control-a"))
                .thenReturn(reasoningOnly("control-b"))
                .thenReturn(reasoningOnly("control-c"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper(), transport);
        AiProviderRequest request = providerRequest("key-upper-bound", "model-upper-bound");

        assertThat(service.complete(request).text()).isEqualTo("warm-final");
        assertThatThrownBy(() -> service.complete(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(failure -> {
                    AiGatewayException exception = (AiGatewayException) failure;
                    assertThat(exception.getFailureCode()).isEqualTo(AiFailureCode.FINAL_CONTENT_MISSING);
                    assertThat(exception.getProviderDispatchCount()).isEqualTo(7);
                });
        verify(transport, times(9)).execute(any());
    }

    @Test
    void completeShouldNotNegotiateOn404() {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(new OutboundResponse(404, Map.of(), "model detail"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper(), transport);

        assertThatThrownBy(() -> service.complete(providerRequest("key-404", "model-g")))
                .isInstanceOf(AiGatewayException.class)
                .extracting(failure -> ((AiGatewayException) failure).getFailureCode())
                .isEqualTo(AiFailureCode.MODEL_NOT_FOUND);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void completeShouldRejectMissingApiKeyAndEmptyMessagesBeforeDispatch() {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper(), transport);

        assertThatThrownBy(() -> service.complete(providerRequest("", "model-h")))
                .isInstanceOf(AiGatewayException.class)
                .extracting(failure -> ((AiGatewayException) failure).getFailureCode())
                .isEqualTo(AiFailureCode.INVALID_CREDENTIAL);
        assertThatThrownBy(() -> service.complete(new AiProviderRequest(
                "key", "https://provider.example.com/v1", "model-h", 0.2d, 100,
                Duration.ofSeconds(5), List.of())))
                .isInstanceOf(AiGatewayException.class)
                .extracting(failure -> ((AiGatewayException) failure).getFailureCode())
                .isEqualTo(AiFailureCode.SCHEMA_INVALID);
        verify(transport, times(0)).execute(any());
    }

    private PinnedHttpTransport successfulTransport(String content, String ignoredKey) {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class))).thenReturn(success(content));
        return transport;
    }

    private OutboundResponse success(String content) {
        return new OutboundResponse(
                200,
                Map.of(),
                "{\"choices\":[{\"message\":{\"content\":" + jsonString(content) + "}}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}");
    }

    private OutboundResponse reasoningOnly(String reasoning) {
        return new OutboundResponse(
                200,
                Map.of(),
                "{\"choices\":[{\"message\":{\"content\":null,\"reasoning_content\":"
                        + jsonString(reasoning) + "}}]}");
    }

    private OutboundResponse unsupported(String parameter) {
        return new OutboundResponse(
                400,
                Map.of(),
                "{\"error\":{\"type\":\"unsupported_parameter\",\"code\":\"unsupported_parameter\","
                        + "\"param\":" + jsonString(parameter) + ",\"message\":\"parameter is not supported\"}}" );
    }

    private String jsonString(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private List<JsonNode> capturedBodies(PinnedHttpTransport transport, ObjectMapper objectMapper) throws Exception {
        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(transport, times(org.mockito.Mockito.mockingDetails(transport).getInvocations().size()))
                .execute(captor.capture());
        List<JsonNode> result = new java.util.ArrayList<>();
        for (OutboundRequest request : captor.getAllValues()) {
            result.add(objectMapper.readTree(request.body()));
        }
        return result;
    }

    private AiProviderRequest providerRequest(String apiKey, String model) {
        return new AiProviderRequest(
                apiKey,
                "https://provider.example.com/v1",
                model,
                0.2d,
                100,
                Duration.ofSeconds(5),
                List.of(
                        AiChatMessage.system("平台策略"),
                        AiChatMessage.user("合成测试输入")));
    }
}
