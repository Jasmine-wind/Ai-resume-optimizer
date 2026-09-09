package com.winter.airesumeoptimizer.module.resume.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.AiClientService;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGateway;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.ai.AiSource;
import com.winter.airesumeoptimizer.module.resume.config.ResumeParseProperties;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeBlockDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumeAiSectionClassifierImplTest {

    private final AiClientService aiClientService = mock(AiClientService.class);
    private final ResumeParseProperties properties = new ResumeParseProperties();
    private final ResumeAiSectionClassifierImpl service = new ResumeAiSectionClassifierImpl(
            properties,
            new ResumeSectionClassifyPromptServiceImpl(new ObjectMapper()),
            aiClientService,
            new ObjectMapper());

    @Test
    void classifyShouldReturnDisabledWhenConfigOff() {
        properties.setAiSectionClassifyEnabled(false);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isFalse();
        assertThat(result.getAiStatus()).isEqualTo("DISABLED");
        assertThat(result.getSkippedReason()).isEqualTo("AI_SECTION_CLASSIFY_DISABLED");
        assertThat(result.getFallbackOccurred()).isFalse();
        verify(aiClientService, never()).complete(anyString());
    }

    @Test
    void classifyShouldParseAiJsonOutput() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.modelName()).thenReturn("test-model");
        when(aiClientService.complete(anyString())).thenReturn("""
                {"items":[
                  {"index":0,"section":"BASIC_INFO","confidence":0.95,"reasonCode":"CONTACT_CONTEXT"},
                  {"index":1,"section":"EDUCATION","confidence":0.9,"reasonCode":"EDUCATION_CONTEXT"}
                ]}
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getDurationMs()).isNotNull();
        assertThat(result.getClassifications()).extracting("section")
                .containsExactly("BASIC_INFO", "EDUCATION");
        assertThat(result.getClassifications()).extracting("reasonCode")
                .containsExactly("CONTACT_CONTEXT", "EDUCATION_CONTEXT");
        verify(aiClientService, times(1)).complete(anyString());
    }

    @Test
    void classifyShouldIgnoreNonExistingIndexFromAiOutput() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenReturn("""
                {"items":[
                  {"index":999,"section":"PROJECTS","confidence":0.95,"reasonCode":"HALLUCINATED_INDEX"},
                  {"index":0,"section":"BASIC_INFO","confidence":0.95,"reasonCode":"CONTACT_CONTEXT"}
                ]}
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("index").containsExactly(0);
        assertThat(result.getClassifications()).extracting("reasonCode").containsExactly("CONTACT_CONTEXT");
    }

    @Test
    void classifyShouldParseJsonWithMarkdownAndExtraText() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenReturn("""
                结果如下：
                ```json
                {"items":[{"index":0,"section":"BASIC_INFO","confidence":0.95}]}
                ```
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("section").containsExactly("BASIC_INFO");
    }

    @Test
    void classifyShouldParseArrayOutput() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenReturn("""
                [
                  {"index":0,"section":"BASIC_INFO","confidence":0.95},
                  {"index":1,"section":"EDUCATION","confidence":0.9}
                ]
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("section")
                .containsExactly("BASIC_INFO", "EDUCATION");
    }

    @Test
    void classifyShouldParseWrappedItemsOutput() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenReturn("""
                {"data":{"items":[{"index":1,"section":"EDUCATION","confidence":0.9}]}}
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("section").containsExactly("EDUCATION");
    }

    @Test
    void classifyShouldParseJsonStringOutput() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenReturn("""
                "{\\"items\\":[{\\"index\\":0,\\"section\\":\\"BASIC_INFO\\",\\"confidence\\":0.95}]}"
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("section").containsExactly("BASIC_INFO");
    }

    @Test
    void classifyShouldParseSingleClassificationObject() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenReturn("""
                {"result":{"index":1,"section":"EDUCATION","confidence":0.9}}
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("section").containsExactly("EDUCATION");
    }

    @Test
    void classifyShouldSendLowConfidenceToOthers() {
        properties.setAiSectionClassifyEnabled(true);
        properties.setAiSectionClassifyMinConfidence(0.7);
        when(aiClientService.complete(anyString())).thenReturn("""
                {"items":[{"index":0,"section":"SKILLS","confidence":0.5}]}
                """);

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications().get(0).getSection()).isEqualTo("OTHERS");
    }

    @Test
    void classifyShouldFallbackWhenAiFails() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.complete(anyString())).thenThrow(new RuntimeException("timeout"));

        var result = service.classify(blocks());

        assertThat(result.shouldApply()).isFalse();
        assertThat(result.getDurationMs()).isNotNull();
        assertThat(result.getFallbackReason()).contains("AI 章节归类失败");
    }

    @Test
    void standalonePreparationShouldFallbackWhenResolvedByokTimesOut() {
        properties.setAiSectionClassifyEnabled(true);
        AiGateway gateway = mock(AiGateway.class);
        AiSelectionSnapshot selection = new AiSelectionSnapshot(
                AiSource.USER_BYOK,
                AiSelectionSnapshot.OPENAI_COMPATIBLE,
                77L,
                5L,
                "https://provider.example.com:443/v1",
                "byok-model",
                "{}",
                null);
        when(gateway.selectionForNewTask(1L)).thenReturn(selection);
        when(gateway.complete(any(), any()))
                .thenThrow(new AiGatewayException(AiFailureCode.TIMEOUT, "AI Provider 请求超时"));
        ResumeAiSectionClassifierImpl standaloneService = new ResumeAiSectionClassifierImpl(
                properties,
                new ResumeSectionClassifyPromptServiceImpl(new ObjectMapper()),
                gateway,
                new ObjectMapper());

        var result = standaloneService.classify(1L, 100L, blocks(), true, null);

        assertThat(result.shouldApply()).isFalse();
        assertThat(result.getFallbackOccurred()).isTrue();
        assertThat(result.getFallbackReason()).contains("AI 章节归类失败");
    }

    @Test
    void classifyShouldSplitLongInputIntoBatches() {
        properties.setAiSectionClassifyEnabled(true);
        properties.setAiSectionClassifyBatchMaxChars(6000);
        when(aiClientService.complete(anyString()))
                .thenReturn("""
                        {"items":[{"index":0,"section":"BASIC_INFO","confidence":0.95}]}
                        """)
                .thenReturn("""
                        {"items":[{"index":19,"section":"SKILLS","confidence":0.95}]}
                        """);

        List<ResumeBlockDTO> longBlocks = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            longBlocks.add(ResumeBlockDTO.builder()
                    .index(index)
                    .text(("Java Spring Boot Redis block " + index + " ").repeat(60))
                    .sourceType("cleanedText")
                    .sourceSection("GENERAL")
                    .build());
        }

        var result = service.classify(longBlocks);

        assertThat(result.shouldApply()).isTrue();
        assertThat(result.getClassifications()).extracting("index").containsExactly(0, 19);
        verify(aiClientService, times(2)).complete(anyString());
    }

    @Test
    void classifyShouldReuseCacheForSameResumeModelPromptAndBlocks() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.modelName()).thenReturn("test-model");
        when(aiClientService.complete(anyString())).thenReturn("""
                {"items":[
                  {"index":0,"section":"BASIC_INFO","confidence":0.95},
                  {"index":1,"section":"EDUCATION","confidence":0.9}
                ]}
                """);

        var first = service.classify(100L, blocks(), true);
        var second = service.classify(100L, blocks(), true);

        assertThat(first.getCacheHit()).isFalse();
        assertThat(second.getCacheHit()).isTrue();
        assertThat(second.getCacheKey()).isEqualTo(first.getCacheKey());
        assertThat(first.getCacheKey())
                .contains("cleanedTextHash=")
                .contains("promptVersion=resume-section-classify-v2")
                .contains("modelName=test-model")
                .contains("parserVersion=" + ResumeParseVersions.PARSER_VERSION)
                .contains("parseMode=BALANCED")
                .contains("blockBuilderVersion=" + ResumeParseVersions.BLOCK_BUILDER_VERSION)
                .contains("sectionRuleVersion=" + ResumeParseVersions.SECTION_RULE_VERSION);
        assertThat(second.getClassifications()).extracting("section")
                .containsExactly("BASIC_INFO", "EDUCATION");
        verify(aiClientService, times(1)).complete(anyString());
    }

    @Test
    void classifyShouldMissCacheWhenParseModeChanges() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.modelName()).thenReturn("test-model");
        when(aiClientService.complete(anyString()))
                .thenReturn("""
                        {"items":[{"index":0,"section":"BASIC_INFO","confidence":0.95}]}
                        """)
                .thenReturn("""
                        {"items":[{"index":0,"section":"SKILLS","confidence":0.95}]}
                        """);

        var balanced = service.classify(100L, List.of(ResumeBlockDTO.builder()
                .index(0)
                .text("张三 13800000000")
                .sourceType("cleanedText")
                .sourceSection("GENERAL")
                .parseMode("BALANCED")
                .build()), true);
        var accurate = service.classify(100L, List.of(ResumeBlockDTO.builder()
                .index(0)
                .text("张三 13800000000")
                .sourceType("cleanedText")
                .sourceSection("GENERAL")
                .parseMode("ACCURATE")
                .build()), true);

        assertThat(balanced.getCacheHit()).isFalse();
        assertThat(accurate.getCacheHit()).isFalse();
        assertThat(accurate.getCacheKey()).isNotEqualTo(balanced.getCacheKey());
        assertThat(accurate.getCacheKey()).contains("parseMode=ACCURATE");
        verify(aiClientService, times(2)).complete(anyString());
    }

    @Test
    void classifyShouldMissCacheWhenBlocksChange() {
        properties.setAiSectionClassifyEnabled(true);
        when(aiClientService.modelName()).thenReturn("test-model");
        when(aiClientService.complete(anyString()))
                .thenReturn("""
                        {"items":[{"index":0,"section":"BASIC_INFO","confidence":0.95}]}
                        """)
                .thenReturn("""
                        {"items":[{"index":0,"section":"SKILLS","confidence":0.95}]}
                        """);

        var first = service.classify(100L, List.of(ResumeBlockDTO.builder()
                .index(0)
                .text("张三 13800000000")
                .sourceType("cleanedText")
                .sourceSection("GENERAL")
                .build()), true);
        var second = service.classify(100L, List.of(ResumeBlockDTO.builder()
                .index(0)
                .text("Java Spring Boot")
                .sourceType("cleanedText")
                .sourceSection("GENERAL")
                .build()), true);

        assertThat(first.getCacheHit()).isFalse();
        assertThat(second.getCacheHit()).isFalse();
        assertThat(second.getCacheKey()).isNotEqualTo(first.getCacheKey());
        verify(aiClientService, times(2)).complete(anyString());
    }

    private List<ResumeBlockDTO> blocks() {
        return List.of(
                ResumeBlockDTO.builder().index(0).text("张三 13800000000").sourceType("cleanedText").sourceSection("GENERAL").parseMode("BALANCED").build(),
                ResumeBlockDTO.builder().index(1).text("教育背景 示例大学 本科").sourceType("cleanedText").sourceSection("GENERAL").parseMode("BALANCED").build());
    }

    @Test
    void classifyShouldSkipBlocksWithLockedSourceSection() {
        properties.setAiSectionClassifyEnabled(true);

        var result = service.classify(List.of(
                ResumeBlockDTO.builder()
                        .index(0)
                        .text("组织校园技术分享活动，获得校级奖项")
                        .sourceType("cleanedText")
                        .sourceSection("CAMPUS_EXPERIENCES")
                        .sectionLocked(true)
                        .build()));

        assertThat(result.shouldApply()).isFalse();
        assertThat(result.getAiStatus()).isEqualTo("SKIPPED");
        assertThat(result.getSkippedReason()).isEqualTo("ALL_BLOCKS_RULE_CONFIRMED");
        assertThat(result.getFallbackOccurred()).isFalse();
        verify(aiClientService, never()).complete(anyString());
    }
}
