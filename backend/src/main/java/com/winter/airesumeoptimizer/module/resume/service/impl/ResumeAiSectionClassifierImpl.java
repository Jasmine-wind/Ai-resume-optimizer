package com.winter.airesumeoptimizer.module.resume.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayRequest;
import com.winter.airesumeoptimizer.infra.ai.AiClientException;
import com.winter.airesumeoptimizer.infra.ai.AiCompletionResult;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGateway;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewaySupport;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.module.resume.config.ResumeParseProperties;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeBlockDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeSectionClassificationDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeSectionClassifyPromptDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeSectionClassifyResultDTO;
import com.winter.airesumeoptimizer.module.resume.service.ResumeAiSectionClassifier;
import com.winter.airesumeoptimizer.module.resume.service.ResumeSectionClassifyPromptService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ResumeAiSectionClassifierImpl implements ResumeAiSectionClassifier {

    private static final Logger log = LoggerFactory.getLogger(ResumeAiSectionClassifierImpl.class);
    private static final Set<String> ALLOWED_SECTIONS = Set.copyOf(ResumeSectionClassifyPromptServiceImpl.allowedSections());
    private static final String AI_STATUS_USED = "USED";
    private static final String AI_STATUS_SKIPPED = "SKIPPED";
    private static final String AI_STATUS_FALLBACK = "FALLBACK";
    private static final String AI_STATUS_DISABLED = "DISABLED";

    private final Map<String, List<ResumeSectionClassificationDTO>> cache = new ConcurrentHashMap<>();
    private final ResumeParseProperties properties;
    private final ResumeSectionClassifyPromptService promptService;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;

    public ResumeAiSectionClassifierImpl(
            ResumeParseProperties properties,
            ResumeSectionClassifyPromptService promptService,
            AiGateway aiGateway,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.promptService = promptService;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResumeSectionClassifyResultDTO classify(List<ResumeBlockDTO> blocks) {
        return classify(null, blocks, null);
    }

    @Override
    public ResumeSectionClassifyResultDTO classify(List<ResumeBlockDTO> blocks, Boolean enabledOverride) {
        return classify(null, blocks, enabledOverride);
    }

    @Override
    public ResumeSectionClassifyResultDTO classify(Long resumeId, List<ResumeBlockDTO> blocks, Boolean enabledOverride) {
        return classify(null, resumeId, blocks, enabledOverride, null);
    }

    @Override
    public ResumeSectionClassifyResultDTO classify(
            Long userId,
            Long resumeId,
            List<ResumeBlockDTO> blocks,
            Boolean enabledOverride,
            AiSelectionSnapshot selection) {
        long startedAt = System.nanoTime();
        boolean failClosedOnProviderFailure = selection != null && selection.isUserByok();
        boolean enabled = enabledOverride == null ? properties.aiSectionClassifyEnabled() : Boolean.TRUE.equals(enabledOverride);
        if (!enabled) {
            return disabled("AI_SECTION_CLASSIFY_DISABLED", startedAt);
        }
        if (blocks == null || blocks.isEmpty()) {
            return skipped(true, "NO_CLASSIFIABLE_BLOCKS", startedAt);
        }
        List<ResumeBlockDTO> classifiableBlocks = blocks.stream()
                .filter(this::shouldClassifyByAi)
                .toList();
        if (classifiableBlocks.isEmpty()) {
            return skipped(true, "ALL_BLOCKS_RULE_CONFIRMED", startedAt);
        }
        if (selection == null && userId != null) {
            try {
                selection = AiGatewaySupport.selectionForNewTask(
                        aiGateway, userId, "RESUME_SECTION_CLASSIFY_SELECTION");
            } catch (AiGatewayException exception) {
                if (exception.getFailureCode() == AiFailureCode.AI_CONFIGURATION_REQUIRED) {
                    return aiFallback("AI_CONFIGURATION_REQUIRED", startedAt);
                }
                throw exception;
            }
        }

        try {
            List<List<ResumeBlockDTO>> batches = partitionBlocks(classifiableBlocks);
            List<ResumeSectionClassifyPromptDTO> prompts = batches.stream()
                    .map(promptService::buildPrompt)
                    .toList();
            String cacheKey = buildCacheKey(userId, resumeId, classifiableBlocks, prompts, selection);
            if (cacheKey != null) {
                List<ResumeSectionClassificationDTO> cached = cache.get(cacheKey);
                if (cached != null) {
                    long durationMs = elapsedMs(startedAt);
                    log.info("Resume AI section classify cache hit: resumeId={}, blockCount={}, durationMs={}",
                            resumeId, classifiableBlocks.size(), durationMs);
                    return ResumeSectionClassifyResultDTO.builder()
                            .aiEnabled(true)
                            .applied(true)
                            .aiInvoked(false)
                            .aiStatus(AI_STATUS_USED)
                            .fallbackOccurred(false)
                            .durationMs(durationMs)
                            .cacheHit(true)
                            .cacheKey(cacheKey)
                            .classifications(copyClassifications(cached))
                            .build();
                }
            }

            List<ResumeSectionClassificationDTO> classifications = new ArrayList<>();
            int batchCount = 0;
            for (int index = 0; index < batches.size(); index++) {
                batchCount++;
                List<ResumeBlockDTO> batch = batches.get(index);
                ResumeSectionClassifyPromptDTO prompt = prompts.get(index);
                String trustedPolicy = prompt.getSystemPrompt() == null || prompt.getSystemPrompt().isBlank()
                        ? "只遵循服务端章节分类输出契约。"
                        : prompt.getSystemPrompt();
                String untrustedData = prompt.getUserPrompt() == null || prompt.getUserPrompt().isBlank()
                        ? prompt.getPrompt()
                        : prompt.getUserPrompt();
                AiCompletionResult completion = AiGatewaySupport.complete(
                        aiGateway,
                        new AiInvocationContext(userId, null, "RESUME_SECTION_CLASSIFY", selection),
                        new AiGatewayRequest("RESUME_SECTION_CLASSIFY", trustedPolicy, untrustedData));
                classifications.addAll(parseOutput(completion.text(), batch));
            }
            if (classifications.isEmpty()) {
                return aiFallback("AI 章节归类结果为空", startedAt);
            }
            long durationMs = elapsedMs(startedAt);
            log.info("Resume AI section classify completed: blockCount={}, batchCount={}, durationMs={}",
                    classifiableBlocks.size(), batchCount, durationMs);
            if (cacheKey != null) {
                cache.put(cacheKey, copyClassifications(classifications));
            }
            return ResumeSectionClassifyResultDTO.builder()
                    .aiEnabled(true)
                    .applied(true)
                    .aiInvoked(true)
                    .aiStatus(AI_STATUS_USED)
                    .fallbackOccurred(false)
                    .durationMs(durationMs)
                    .cacheHit(false)
                    .cacheKey(cacheKey)
                    .classifications(classifications)
                    .build();
        } catch (RuntimeException exception) {
            if (failClosedOnProviderFailure) {
                if (exception instanceof AiGatewayException gatewayException) {
                    throw gatewayException;
                }
                throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI 章节归类结果格式异常");
            }
            // Parser/provider messages can contain echoed resume content; keep diagnostics non-content only.
            log.warn("Resume AI section classify fallback: exceptionType={}", exception.getClass().getSimpleName());
            return aiFallback("AI 章节归类失败", startedAt);
        }
    }

    private boolean shouldClassifyByAi(ResumeBlockDTO block) {
        if (block == null || block.getText() == null || block.getText().isBlank()) {
            return false;
        }
        String sourceSection = block.getSourceSection();
        return !Boolean.TRUE.equals(block.getSectionLocked())
                || sourceSection == null
                || sourceSection.isBlank()
                || "GENERAL".equals(sourceSection)
                || "OTHERS".equals(sourceSection);
    }

    private String buildCacheKey(
            Long userId,
            Long resumeId,
            List<ResumeBlockDTO> blocks,
            List<ResumeSectionClassifyPromptDTO> prompts,
            AiSelectionSnapshot selection) {
        if (resumeId == null) {
            return null;
        }
        String promptVersion = prompts.stream()
                .map(ResumeSectionClassifyPromptDTO::getPromptVersion)
                .distinct()
                .collect(Collectors.joining("+"));
        String selectionIdentity = selection == null
                ? "legacy-system"
                : selection.cacheIdentity(userId);
        String modelIdentity = selection == null
                ? nullToUnknown(AiGatewaySupport.modelName(
                        aiGateway,
                        new AiInvocationContext(userId, null, "RESUME_SECTION_CLASSIFY_CACHE", null)))
                : nullToUnknown(selection.model());
        return "section"
                + ":userId=" + nullToUnknown(userId)
                + ":resumeId=" + nullToUnknown(resumeId)
                + ":cleanedTextHash=" + hashCleanedText(blocks)
                + ":promptVersion=" + nullToUnknown(promptVersion)
                + ":selection=" + selectionIdentity
                + ":modelName=" + modelIdentity
                + ":parserVersion=" + ResumeParseVersions.PARSER_VERSION
                + ":parseMode=" + parseMode(blocks)
                + ":blockBuilderVersion=" + ResumeParseVersions.BLOCK_BUILDER_VERSION
                + ":sectionRuleVersion=" + ResumeParseVersions.SECTION_RULE_VERSION
                + ":blockContextHash=" + hashBlocks(blocks);
    }

    private String hashCleanedText(List<ResumeBlockDTO> blocks) {
        StringBuilder builder = new StringBuilder();
        for (ResumeBlockDTO block : blocks == null ? List.<ResumeBlockDTO>of() : blocks) {
            builder.append(nullToUnknown(block == null ? null : block.getText())).append('\n');
        }
        return sha256(builder.toString());
    }

    private String hashBlocks(List<ResumeBlockDTO> blocks) {
        StringBuilder builder = new StringBuilder();
        for (ResumeBlockDTO block : blocks) {
            builder.append(nullToUnknown(block == null ? null : block.getIndex())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getSourceType())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getSourceSection())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getRuleSection())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getRuleConfidence())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getSourceSectionConfidence())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getLockedLevel())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getResumeTypeHint())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getParseMode())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getPrevText())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getNextText())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getSectionLocked())).append('\t')
                    .append(nullToUnknown(block == null ? null : block.getText())).append('\n');
        }
        return sha256(builder.toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String parseMode(List<ResumeBlockDTO> blocks) {
        if (blocks == null) {
            return "unknown";
        }
        return blocks.stream()
                .map(ResumeBlockDTO::getParseMode)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("unknown");
    }

    private List<ResumeSectionClassificationDTO> copyClassifications(List<ResumeSectionClassificationDTO> classifications) {
        return classifications.stream()
                .map(item -> ResumeSectionClassificationDTO.builder()
                        .index(item.getIndex())
                        .section(item.getSection())
                        .confidence(item.getConfidence())
                        .reasonCode(item.getReasonCode())
                        .build())
                .toList();
    }

    private String nullToUnknown(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "unknown" : text;
    }

    private List<List<ResumeBlockDTO>> partitionBlocks(List<ResumeBlockDTO> blocks) {
        List<List<ResumeBlockDTO>> batches = new ArrayList<>();
        List<ResumeBlockDTO> current = new ArrayList<>();
        int currentChars = 0;
        int maxChars = properties.aiSectionClassifyBatchMaxChars();

        for (ResumeBlockDTO block : blocks) {
            int blockChars = estimatePromptChars(block);
            if (!current.isEmpty() && currentChars + blockChars > maxChars) {
                batches.add(List.copyOf(current));
                current.clear();
                currentChars = 0;
            }
            current.add(block);
            currentChars += blockChars;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private int estimatePromptChars(ResumeBlockDTO block) {
        if (block == null || block.getText() == null) {
            return 80;
        }
        int neighborChars = nullSafeLength(block.getPrevText()) + nullSafeLength(block.getNextText());
        return Math.min(block.getText().length(), 260) + Math.min(neighborChars, 360) + 180;
    }

    private int nullSafeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private List<ResumeSectionClassificationDTO> parseOutput(String aiOutput, List<ResumeBlockDTO> blocks) {
        RuntimeException lastException = null;
        for (String candidate : extractJsonCandidates(aiOutput)) {
            try {
                JsonNode root = readJsonCandidate(candidate);
                JsonNode itemsNode = unwrapItems(root);
                return parseItems(itemsNode, blocks);
            } catch (RuntimeException exception) {
                lastException = exception;
            } catch (Exception exception) {
                lastException = new IllegalArgumentException(exception);
            }
        }
        if (lastException == null) {
            lastException = new IllegalArgumentException("AI 输出中未找到 JSON");
        }
        // Provider output can echo resume content. Keep only non-content diagnostics.
        log.warn("Resume AI section classify JSON parse failed: exceptionType={}, outputLength={}",
                lastException.getClass().getSimpleName(),
                aiOutput == null ? 0 : aiOutput.length());
        throw new IllegalArgumentException("AI 章节归类 JSON 解析失败", lastException);
    }

    private JsonNode readJsonCandidate(String candidate) throws Exception {
        JsonNode root = objectMapper.readTree(candidate);
        if (root.isTextual()) {
            return objectMapper.readTree(root.asText());
        }
        return root;
    }

    private JsonNode unwrapItems(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("AI 输出为空");
        }
        if (root.isArray()) {
            return root;
        }
        if (root.isObject() && root.has("index") && root.has("section")) {
            ArrayNode singleItem = objectMapper.createArrayNode();
            singleItem.add(root);
            return singleItem;
        }
        for (String fieldName : List.of("items", "classifications", "classification", "results", "result", "data", "output")) {
            JsonNode node = root.path(fieldName);
            if (node.isArray()) {
                return node;
            }
            if (node.isObject() && node.has("index") && node.has("section")) {
                ArrayNode singleItem = objectMapper.createArrayNode();
                singleItem.add(node);
                return singleItem;
            }
            if (node.isObject()) {
                JsonNode nestedItems = node.path("items");
                if (nestedItems.isArray()) {
                    return nestedItems;
                }
            }
        }
        throw new IllegalArgumentException("AI 输出缺少 items 数组");
    }

    private List<ResumeSectionClassificationDTO> parseItems(JsonNode itemsNode, List<ResumeBlockDTO> blocks) {
        try {
            if (!itemsNode.isArray()) {
                throw new IllegalArgumentException("AI 输出缺少 items 数组");
            }

            Set<Integer> validIndexes = blocks.stream()
                    .map(ResumeBlockDTO::getIndex)
                    .collect(Collectors.toSet());
            List<ResumeSectionClassificationDTO> result = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                int index = itemNode.path("index").asInt(-1);
                String section = itemNode.path("section").asText("");
                double confidence = itemNode.path("confidence").asDouble(0);
                String reasonCode = itemNode.path("reasonCode").asText(null);
                if (!validIndexes.contains(index)) {
                    continue;
                }
                if (!ALLOWED_SECTIONS.contains(section)) {
                    section = "OTHERS";
                    confidence = Math.min(confidence, properties.minConfidence());
                }
                if (confidence < properties.minConfidence()) {
                    section = "OTHERS";
                }
                result.add(ResumeSectionClassificationDTO.builder()
                        .index(index)
                        .section(section)
                        .confidence(confidence)
                        .reasonCode(reasonCode)
                        .build());
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI 章节归类 JSON 解析失败", exception);
        }
    }

    private ResumeSectionClassifyResultDTO disabled(String reasonCode, long startedAt) {
        return ResumeSectionClassifyResultDTO.builder()
                .aiEnabled(false)
                .applied(false)
                .aiInvoked(false)
                .aiStatus(AI_STATUS_DISABLED)
                .skippedReason(reasonCode)
                .fallbackOccurred(false)
                .durationMs(elapsedMs(startedAt))
                .cacheHit(false)
                .classifications(List.of())
                .build();
    }

    private ResumeSectionClassifyResultDTO skipped(boolean enabled, String reasonCode, long startedAt) {
        return ResumeSectionClassifyResultDTO.builder()
                .aiEnabled(enabled)
                .applied(false)
                .aiInvoked(false)
                .aiStatus(AI_STATUS_SKIPPED)
                .skippedReason(reasonCode)
                .fallbackOccurred(false)
                .durationMs(elapsedMs(startedAt))
                .cacheHit(false)
                .classifications(List.of())
                .build();
    }

    private ResumeSectionClassifyResultDTO aiFallback(String reason, long startedAt) {
        return ResumeSectionClassifyResultDTO.builder()
                .aiEnabled(true)
                .applied(false)
                .aiInvoked(true)
                .aiStatus(AI_STATUS_FALLBACK)
                .fallbackOccurred(true)
                .fallbackReason(reason)
                .durationMs(elapsedMs(startedAt))
                .cacheHit(false)
                .classifications(List.of())
                .build();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private List<String> extractJsonCandidates(String value) {
        if (value == null) {
            return List.of();
        }
        String stripped = stripMarkdownFence(value);
        List<String> candidates = new ArrayList<>();
        candidates.add(stripped);
        String unescaped = unescapeJsonCandidate(stripped);
        if (!unescaped.equals(stripped)) {
            candidates.add(unescaped);
        }
        if (looksLikeJsonContainer(stripped)) {
            addJsonCandidate(candidates, stripped);
        }

        for (int index = 0; index < stripped.length(); index++) {
            char current = stripped.charAt(index);
            if (current == '{' || current == '[') {
                String candidate = extractBalancedJson(stripped, index, current);
                if (candidate != null) {
                    addJsonCandidate(candidates, candidate);
                    addJsonCandidate(candidates, unescapeJsonCandidate(candidate));
                }
            }
        }
        return candidates;
    }

    private void addJsonCandidate(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank() && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private String unescapeJsonCandidate(String value) {
        if (value == null || !value.contains("\\\"")) {
            return value;
        }
        return value.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private String stripMarkdownFence(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("(?i)^```(?:json)?\\s*", "");
            stripped = stripped.replaceFirst("\\s*```$", "");
        }
        return stripped.strip();
    }

    private boolean looksLikeJsonContainer(String value) {
        return (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"));
    }

    private String extractBalancedJson(String value, int start, char openChar) {
        char closeChar = openChar == '{' ? '}' : ']';
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = inString;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == openChar) {
                depth++;
            } else if (current == closeChar) {
                depth--;
                if (depth == 0) {
                    return value.substring(start, index + 1);
                }
            }
        }
        return null;
    }

}
