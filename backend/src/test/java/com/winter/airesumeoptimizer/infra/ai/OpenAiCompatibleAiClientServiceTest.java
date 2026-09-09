package com.winter.airesumeoptimizer.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundRequest;
import com.winter.airesumeoptimizer.infra.ai.transport.OutboundResponse;
import com.winter.airesumeoptimizer.infra.ai.transport.PinnedHttpTransport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenAiCompatibleAiClientServiceTest {

    @Test
    void extractContentShouldReturnAssistantContent() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThat(service.extractContent("""
                {"choices":[{"message":{"content":"基础 AI 调用成功"}}]}
                """)).isEqualTo("基础 AI 调用成功");
    }

    @Test
    void extractContentShouldReadArrayContentText() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThat(service.extractContent("""
                {"choices":[{"message":{"content":[{"type":"text","text":"数组文本内容"}]}}]}
                """)).isEqualTo("数组文本内容");
    }

    @Test
    void extractContentShouldReportLengthFinishReason() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThatThrownBy(() -> service.extractContent("""
                {"choices":[{"finish_reason":"length","message":{"content":""}}]}
                """))
                .isInstanceOf(AiClientException.class)
                .hasMessage("AI 响应中缺少文本内容，可能是 max_tokens 不足，请调大 AI_MAX_TOKENS 后重试");
    }

    @Test
    void extractContentShouldReadLegacyChoiceTextWhenChatMessageContentIsMissing() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThat(service.extractContent("""
                {"choices":[{"text":"兼容的文本内容","message":{}}]}
                """))
                .isEqualTo("兼容的文本内容");
    }

    @Test
    void extractContentShouldExplainReasoningOnlyResponses() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThatThrownBy(() -> service.extractContent("""
                {"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning_content":"只返回了推理过程"}}]}
                """))
                .isInstanceOf(AiClientException.class)
                .hasMessage("AI Provider 只返回了推理内容，没有返回最终文本；请改用支持 Chat Completions 文本输出的模型");
    }

    @Test
    void completeShouldRejectMissingApiKey() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThatThrownBy(() -> service.complete(providerRequest("", List.of(AiChatMessage.user("测试输入")))))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.INVALID_CREDENTIAL);
    }

    @Test
    void completeShouldSendRoleSeparatedMessages() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(new OutboundResponse(
                        200,
                        java.util.Map.of(),
                        "{\"choices\":[{\"message\":{\"content\":\"改写结果\"}}]}"));
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(objectMapper, transport);

        String content = service.complete(providerRequest("test-key", List.of(
                AiChatMessage.system("平台真实性策略"),
                AiChatMessage.user("不可信简历内容")))).text();

        assertThat(content).isEqualTo("改写结果");
        ArgumentCaptor<OutboundRequest> requestCaptor = ArgumentCaptor.forClass(OutboundRequest.class);
        org.mockito.Mockito.verify(transport).execute(requestCaptor.capture());
        JsonNode messages = objectMapper.readTree(requestCaptor.getValue().body()).path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("平台真实性策略");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("不可信简历内容");
        assertThat(requestCaptor.getValue().baseUrl()).isEqualTo("https://provider.example.com/v1");
        assertThat(requestCaptor.getValue().endpointPath()).isEqualTo("/chat/completions");
    }

    @Test
    void completeShouldRejectEmptyMessageList() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThatThrownBy(() -> service.complete(providerRequest("test-key", List.of())))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.SCHEMA_INVALID);
    }

    @Test
    void completeShouldRejectBlankMessageContent() {
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper());

        assertThatThrownBy(() -> service.complete(providerRequest(
                "test-key",
                List.of(AiChatMessage.system("  ")))))
                .isInstanceOf(AiGatewayException.class)
                .extracting(exception -> ((AiGatewayException) exception).getFailureCode())
                .isEqualTo(AiFailureCode.SCHEMA_INVALID);
    }

    @Test
    void completeShouldMap401And403ToDistinctStableCodesWithoutRawBodies() {
        PinnedHttpTransport transport = mock(PinnedHttpTransport.class);
        OpenAiCompatibleAiClientService service = new OpenAiCompatibleAiClientService(new ObjectMapper(), transport);
        when(transport.execute(any(OutboundRequest.class)))
                .thenReturn(new OutboundResponse(401, java.util.Map.of(), "raw secret error"))
                .thenReturn(new OutboundResponse(403, java.util.Map.of(), "raw permission error"));

        assertThatThrownBy(() -> service.complete(providerRequest(
                "test-key", List.of(AiChatMessage.user("data")))))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(exception -> {
                    AiGatewayException gatewayException = (AiGatewayException) exception;
                    assertThat(gatewayException.getFailureCode()).isEqualTo(AiFailureCode.INVALID_CREDENTIAL);
                    assertThat(gatewayException.getMessage()).doesNotContain("raw secret error");
                });
        assertThatThrownBy(() -> service.complete(providerRequest(
                "test-key", List.of(AiChatMessage.user("data")))))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(exception -> {
                    AiGatewayException gatewayException = (AiGatewayException) exception;
                    assertThat(gatewayException.getFailureCode()).isEqualTo(AiFailureCode.PROVIDER_UNAUTHORIZED);
                    assertThat(gatewayException.getMessage()).doesNotContain("raw permission error");
                });
    }

    private AiProviderRequest providerRequest(String apiKey, List<AiChatMessage> messages) {
        return new AiProviderRequest(
                apiKey,
                "https://provider.example.com/v1",
                "test-model",
                0.2d,
                100,
                Duration.ofSeconds(5),
                messages);
    }
}
