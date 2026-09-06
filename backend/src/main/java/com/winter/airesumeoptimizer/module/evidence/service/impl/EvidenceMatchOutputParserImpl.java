package com.winter.airesumeoptimizer.module.evidence.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.module.evidence.dto.EvidenceMatchOutcomeDTO;
import com.winter.airesumeoptimizer.module.evidence.dto.EvidenceQuoteDTO;
import com.winter.airesumeoptimizer.module.evidence.dto.EvidenceRequirementEvaluationDTO;
import com.winter.airesumeoptimizer.module.evidence.enums.EvidenceMatchLevel;
import com.winter.airesumeoptimizer.module.evidence.enums.EvidenceSupportLevel;
import com.winter.airesumeoptimizer.module.evidence.enums.RequirementImportance;
import com.winter.airesumeoptimizer.module.evidence.service.EvidenceMatchOutputParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class EvidenceMatchOutputParserImpl implements EvidenceMatchOutputParser {

    private static final int MAX_AI_OUTPUT_LENGTH = 100_000;
    private static final int MAX_REQUIREMENT_COUNT = 10;
    private static final int MAX_EVIDENCE_PER_REQUIREMENT = 3;
    private static final int MAX_REQUIREMENT_TEXT_LENGTH = 500;
    private static final int MAX_SECTION_LABEL_LENGTH = 100;
    private static final int MAX_EVIDENCE_TEXT_LENGTH = 1000;
    private static final int MIN_QUOTE_LENGTH = 2;
    private static final String MATCHED_CONCLUSION = "当前材料中已有足够证据支持这条要求。";
    private static final String PARTIAL_EVIDENCE_CONCLUSION =
            "当前材料中有相关证据，但还不足以完整支持这条要求。";
    private static final String PARTIAL_EVIDENCE_SUGGESTION =
            "可强化材料中已有的相关表达；新增技术、数字或成果前必须先由用户确认真实事实。";
    private static final String NO_EVIDENCE_CONCLUSION = "当前材料中没有找到支持这条要求的证据。";
    private static final String NO_EVIDENCE_SUGGESTION =
            "如确有相关事实，请由用户补充或确认；在此之前不得写入简历。";
    private static final List<String> JOB_REQUIREMENT_FIELDS = List.of(
            "requiredSkills", "bonusSkills", "experienceSignals", "responsibilities");
    private static final Pattern LATIN_ANCHOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+#.\\-]{1,}");
    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{3,}");
    private static final Set<String> GENERIC_HAN_ANCHORS = Set.of(
            "负责", "具备", "熟悉", "了解", "掌握", "能够", "相关", "经验", "能力", "优先", "要求", "岗位",
            "工作", "项目", "开发", "系统", "技术", "使用", "进行", "完成", "参与", "具有", "良好", "较强");

    private final ObjectMapper objectMapper;
    private final ObjectReader lenientJsonReader;

    public EvidenceMatchOutputParserImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.lenientJsonReader = objectMapper.reader()
                .with(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .with(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                .with(JsonParser.Feature.ALLOW_COMMENTS)
                .with(JsonParser.Feature.ALLOW_TRAILING_COMMA);
    }

    @Override
    public EvidenceMatchOutcomeDTO parse(
            String aiOutput,
            String frozenJobDescription,
            String jobStructuredContent,
            String resumeCorpus) {
        JsonNode root = readJson(aiOutput);
        if (!root.isObject()) {
            throw invalidOutput("岗位证据分析结果必须是 JSON 对象");
        }
        List<JobRequirement> allowedRequirements = readAllowedRequirements(
                frozenJobDescription,
                jobStructuredContent);
        if (allowedRequirements.isEmpty()) {
            throw invalidOutput("目标岗位解析结果没有可追溯到 JD 原文的要求");
        }

        JsonNode requirementsNode = root.get("requirements");
        if (requirementsNode == null || !requirementsNode.isArray()) {
            throw invalidOutput("岗位证据分析结果缺少 requirements");
        }

        Set<String> seenRequirementTexts = new HashSet<>();
        List<EvidenceRequirementEvaluationDTO> requirements = new ArrayList<>();
        for (JsonNode item : requirementsNode) {
            if (!item.isObject()) {
                continue;
            }
            EvidenceRequirementEvaluationDTO evaluation = readRequirement(item, allowedRequirements, resumeCorpus);
            if (evaluation == null) {
                continue;
            }
            if (!seenRequirementTexts.add(normalize(evaluation.getRequirementText()))) {
                continue;
            }
            requirements.add(evaluation);
            if (requirements.size() == MAX_REQUIREMENT_COUNT) {
                break;
            }
        }

        if (requirements.isEmpty()) {
            throw invalidOutput("岗位证据分析结果没有可用的岗位要求");
        }
        return EvidenceMatchOutcomeDTO.builder()
                .requirements(List.copyOf(requirements))
                .build();
    }

    private EvidenceRequirementEvaluationDTO readRequirement(
            JsonNode item,
            List<JobRequirement> allowedRequirements,
            String resumeCorpus) {
        String proposedRequirement = readText(item.get("requirement"), MAX_REQUIREMENT_TEXT_LENGTH);
        if (proposedRequirement.isBlank() || readMatchLevel(item.get("matchLevel")) == null) {
            return null;
        }
        JobRequirement sourceRequirement = findSourceRequirement(proposedRequirement, allowedRequirements);
        if (sourceRequirement == null) {
            return null;
        }

        List<EvidenceQuoteDTO> evidences = readEvidences(
                item.get("evidences"),
                resumeCorpus,
                sourceRequirement.text());
        EvidenceMatchLevel effectiveLevel = deriveMatchLevel(evidences);
        return EvidenceRequirementEvaluationDTO.builder()
                .requirementText(sourceRequirement.text())
                .importance(sourceRequirement.importance())
                .matchLevel(effectiveLevel)
                .conclusion(conclusionFor(effectiveLevel))
                .suggestion(suggestionFor(effectiveLevel))
                .evidences(List.copyOf(evidences))
                .build();
    }

    private List<JobRequirement> readAllowedRequirements(
            String frozenJobDescription,
            String jobStructuredContent) {
        if (frozenJobDescription == null || frozenJobDescription.isBlank()
                || jobStructuredContent == null || jobStructuredContent.isBlank()) {
            throw invalidOutput("目标岗位输入不能为空");
        }
        JsonNode jobRoot;
        try {
            jobRoot = objectMapper.readTree(jobStructuredContent);
        } catch (Exception exception) {
            throw invalidOutput("目标岗位结构化解析结果不是合法 JSON");
        }
        if (jobRoot == null || !jobRoot.isObject()) {
            throw invalidOutput("目标岗位结构化解析结果必须是 JSON 对象");
        }

        List<JobRequirement> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String field : JOB_REQUIREMENT_FIELDS) {
            JsonNode items = jobRoot.get(field);
            if (items == null || !items.isArray()) {
                continue;
            }
            RequirementImportance importance = "bonusSkills".equals(field)
                    ? RequirementImportance.BONUS
                    : RequirementImportance.REQUIRED;
            for (JsonNode item : items) {
                String text = readText(item, MAX_REQUIREMENT_TEXT_LENGTH);
                // 岗位解析同样是 AI 输出。正式 Requirement 必须能完整回溯到冻结 JD，
                // 不能仅凭共享技能词接受被改写、增强甚至编造的结构化条目。
                if (text.isBlank() || !normalize(frozenJobDescription).contains(normalize(text))) {
                    continue;
                }
                if (seen.add(normalize(text))) {
                    values.add(new JobRequirement(text, importance));
                }
            }
        }
        return List.copyOf(values);
    }

    private JobRequirement findSourceRequirement(
            String proposedRequirement,
            List<JobRequirement> allowedRequirements) {
        String normalizedProposed = normalize(proposedRequirement);
        for (JobRequirement allowed : allowedRequirements) {
            String normalizedAllowed = normalize(allowed.text());
            if (normalizedProposed.equals(normalizedAllowed)
                    || ((normalizedProposed.contains(normalizedAllowed)
                            || normalizedAllowed.contains(normalizedProposed))
                            && hasMeaningfulOverlap(proposedRequirement, allowed.text()))) {
                return allowed;
            }
        }
        for (JobRequirement allowed : allowedRequirements) {
            if (hasMeaningfulOverlap(proposedRequirement, allowed.text())) {
                return allowed;
            }
        }
        return null;
    }

    private List<EvidenceQuoteDTO> readEvidences(
            JsonNode node,
            String resumeCorpus,
            String sourceRequirement) {
        if (node == null || node.isNull() || !node.isArray() || resumeCorpus == null) {
            return List.of();
        }
        List<EvidenceQuoteDTO> values = new ArrayList<>();
        Set<String> seenQuotes = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String quote = readText(item.get("quote"), MAX_EVIDENCE_TEXT_LENGTH);
            if (quote.length() < MIN_QUOTE_LENGTH
                    || !resumeCorpus.contains(quote)
                    || !hasMeaningfulOverlap(sourceRequirement, quote)
                    || !seenQuotes.add(quote)) {
                continue;
            }
            values.add(EvidenceQuoteDTO.builder()
                    .sectionLabel(readText(item.get("section"), MAX_SECTION_LABEL_LENGTH))
                    .quote(quote)
                    .supportLevel(readSupportLevel(item.get("supportLevel")))
                    .build());
            if (values.size() == MAX_EVIDENCE_PER_REQUIREMENT) {
                break;
            }
        }
        return List.copyOf(values);
    }

    private EvidenceMatchLevel deriveMatchLevel(List<EvidenceQuoteDTO> evidences) {
        if (evidences.isEmpty()) {
            return EvidenceMatchLevel.NO_EVIDENCE;
        }
        return evidences.stream()
                .anyMatch(evidence -> evidence.getSupportLevel() == EvidenceSupportLevel.SUFFICIENT)
                        ? EvidenceMatchLevel.MATCHED
                        : EvidenceMatchLevel.PARTIAL_EVIDENCE;
    }

    private String conclusionFor(EvidenceMatchLevel level) {
        return switch (level) {
            case MATCHED -> MATCHED_CONCLUSION;
            case PARTIAL_EVIDENCE -> PARTIAL_EVIDENCE_CONCLUSION;
            case NO_EVIDENCE -> NO_EVIDENCE_CONCLUSION;
        };
    }

    private String suggestionFor(EvidenceMatchLevel level) {
        return switch (level) {
            case MATCHED -> "";
            case PARTIAL_EVIDENCE -> PARTIAL_EVIDENCE_SUGGESTION;
            case NO_EVIDENCE -> NO_EVIDENCE_SUGGESTION;
        };
    }

    private boolean hasMeaningfulOverlap(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return false;
        }
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        String shorter = normalizedLeft.length() <= normalizedRight.length() ? left : right;
        if ((normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft))
                && !extractAnchors(shorter).isEmpty()) {
            return true;
        }
        Set<String> leftAnchors = extractAnchors(left);
        leftAnchors.retainAll(extractAnchors(right));
        return !leftAnchors.isEmpty();
    }

    private Set<String> extractAnchors(String value) {
        Set<String> anchors = new LinkedHashSet<>();
        Matcher latinMatcher = LATIN_ANCHOR.matcher(value);
        while (latinMatcher.find()) {
            anchors.add(latinMatcher.group().toLowerCase(Locale.ROOT));
        }
        Matcher hanMatcher = HAN_SEQUENCE.matcher(value);
        while (hanMatcher.find()) {
            String sequence = hanMatcher.group();
            for (int index = 0; index < sequence.length() - 2; index++) {
                String anchor = sequence.substring(index, index + 3);
                if (!GENERIC_HAN_ANCHORS.contains(anchor)) {
                    anchors.add(anchor);
                }
            }
        }
        return anchors;
    }

    private EvidenceMatchLevel readMatchLevel(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return EvidenceMatchLevel.valueOf(node.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private EvidenceSupportLevel readSupportLevel(JsonNode node) {
        if (node != null && node.isTextual()) {
            try {
                return EvidenceSupportLevel.valueOf(node.asText().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 非法支持程度按部分证据处理，不能据此授予 MATCHED。
            }
        }
        return EvidenceSupportLevel.PARTIAL;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '+' || current == '#') {
                builder.append(Character.toLowerCase(current));
            }
        }
        return builder.toString();
    }

    private JsonNode readJson(String aiOutput) {
        if (aiOutput == null || aiOutput.isBlank()) {
            throw invalidOutput("岗位证据分析结果不能为空");
        }
        if (aiOutput.length() > MAX_AI_OUTPUT_LENGTH) {
            throw invalidOutput("岗位证据分析结果过长");
        }
        JsonNode firstObject = null;
        for (String candidate : extractJsonCandidates(aiOutput.strip())) {
            JsonNode root = tryReadJson(candidate);
            if (root == null || !root.isObject()) {
                continue;
            }
            if (root.has("requirements")) {
                return root;
            }
            if (firstObject == null) {
                firstObject = root;
            }
        }
        if (firstObject != null) {
            return firstObject;
        }
        throw invalidOutput("岗位证据分析结果不是合法 JSON");
    }

    private List<String> extractJsonCandidates(String value) {
        String stripped = stripJsonCodeFence(value);
        List<String> candidates = new ArrayList<>();
        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            candidates.add(stripped);
        }
        candidates.addAll(findBalancedJsonObjects(stripped));
        if (candidates.isEmpty()) {
            candidates.add(stripped);
        }
        return candidates;
    }

    private String stripJsonCodeFence(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("^```(?:json|JSON)?\\s*", "");
            stripped = stripped.replaceFirst("\\s*```$", "").strip();
        }
        return stripped;
    }

    private List<String> findBalancedJsonObjects(String value) {
        List<String> candidates = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int index = 0; index < value.length(); index++) {
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
            if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    candidates.add(value.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return candidates;
    }

    private JsonNode tryReadJson(String value) {
        try {
            return lenientJsonReader.readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private String readText(JsonNode node, int maxLength) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return "";
        }
        String text = node.asText().strip();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private BusinessException invalidOutput(String message) {
        return new BusinessException(502, message);
    }

    private record JobRequirement(String text, RequirementImportance importance) {
    }
}
