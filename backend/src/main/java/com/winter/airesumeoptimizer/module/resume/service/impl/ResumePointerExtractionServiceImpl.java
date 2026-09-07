package com.winter.airesumeoptimizer.module.resume.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayRequest;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGateway;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewaySupport;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeIndexedLineDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeParseMode;
import com.winter.airesumeoptimizer.module.resume.dto.ResumePointerExtractionResultDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumePointerExtractorType;
import com.winter.airesumeoptimizer.module.resume.service.ResumePointerExtractionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class ResumePointerExtractionServiceImpl implements ResumePointerExtractionService {

    static final String PROMPT_VERSION = "resume-pointer-extraction-v2.9.19.1";

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResumePointerExtractionResultDTO> cache = new ConcurrentHashMap<>();

    public ResumePointerExtractionServiceImpl(AiGateway aiGateway, ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResumePointerExtractionResultDTO extract(
            Long resumeId,
            List<ResumeIndexedLineDTO> indexedLines,
            ResumeParseMode parseMode,
            ResumePointerExtractorType extractorType) {
        return extract(null, resumeId, indexedLines, parseMode, extractorType, null);
    }

    @Override
    public ResumePointerExtractionResultDTO extract(
            Long userId,
            Long resumeId,
            List<ResumeIndexedLineDTO> indexedLines,
            ResumeParseMode parseMode,
            ResumePointerExtractorType extractorType,
            AiSelectionSnapshot selection) {
        if (parseMode == ResumeParseMode.FAST || indexedLines == null || indexedLines.isEmpty()) {
            return empty(extractorType, false, false);
        }
        if (selection == null && userId != null) {
            try {
                selection = AiGatewaySupport.selectionForNewTask(
                        aiGateway, userId, "RESUME_POINTER_EXTRACTION_SELECTION");
            } catch (AiGatewayException exception) {
                if (exception.getFailureCode() == AiFailureCode.AI_CONFIGURATION_REQUIRED) {
                    return empty(extractorType, false, false);
                }
                throw exception;
            }
        }
        String cacheKey = cacheKey(userId, resumeId, indexedLines, parseMode, extractorType, selection);
        ResumePointerExtractionResultDTO cached = cache.get(cacheKey);
        if (cached != null) {
            ResumePointerExtractionResultDTO cachedCopy = copy(cached);
            cachedCopy.setCacheHit(true);
            cachedCopy.setAiInvoked(false);
            return cachedCopy;
        }
        try {
            String prompt = buildPrompt(indexedLines, extractorType);
            String output = AiGatewaySupport.completeText(
                    aiGateway,
                    new AiInvocationContext(userId, null, "RESUME_POINTER_EXTRACTION", selection),
                    new AiGatewayRequest(
                            "RESUME_POINTER_EXTRACTION",
                            "只输出合法 JSON；不得改写或补充简历事实。",
                            prompt));
            ResumePointerExtractionResultDTO result = objectMapper.readValue(extractJson(output), ResumePointerExtractionResultDTO.class);
            sanitize(result, indexedLines, extractorType);
            result.setExtractorType(extractorType);
            result.setPromptVersion(PROMPT_VERSION);
            result.setAiInvoked(true);
            result.setCacheHit(false);
            cache.put(cacheKey, copy(result));
            return result;
        } catch (RuntimeException | JsonProcessingException exception) {
            if (selection != null && selection.isUserByok()) {
                if (exception instanceof AiGatewayException gatewayException) {
                    throw gatewayException;
                }
                throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI 简历定位结果格式异常");
            }
            return empty(extractorType, true, false);
        }
    }

    private ResumePointerExtractionResultDTO empty(ResumePointerExtractorType extractorType, boolean aiInvoked, boolean cacheHit) {
        return ResumePointerExtractionResultDTO.builder()
                .extractorType(extractorType)
                .promptVersion(PROMPT_VERSION)
                .aiInvoked(aiInvoked)
                .cacheHit(cacheHit)
                .basicInfoPointers(List.of())
                .educationPointers(List.of())
                .workExperiencePointers(List.of())
                .projectPointers(List.of())
                .achievementPointers(List.of())
                .summaryPointers(List.of())
                .build();
    }

    private String buildPrompt(List<ResumeIndexedLineDTO> indexedLines, ResumePointerExtractorType extractorType) throws JsonProcessingException {
        List<Map<String, Object>> lines = indexedLines.stream()
                .filter(line -> !Boolean.TRUE.equals(line.getIsNoise()))
                .map(line -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", line.getLineId());
                    item.put("text", line.getText());
                    item.put("sectionHint", line.getSectionHint());
                    return item;
                })
                .toList();
        return """
                你是简历字段指针抽取器，只负责当前 extractorType=%s。
                任务边界：
                1. 只能返回 lineId 或 lineRange，不要返回长文本。
                2. 不要输出 Markdown，不要解释，不要生成输入行里不存在的内容。
                3. 字段不存在时返回 null 或空数组。
                4. lineRange 使用 [startLine,endLine]，必须合法且 startLine <= endLine。
                5. 字段标签行不能作为实体行，例如 公司名称、职位名称、工作时间、项目名称、项目描述。
                6. 输出 JSON 根对象，按 extractorType 只填对应 pointer 数组，其他数组可为空。
                可用结构：
                basicInfoPointers: [{"nameLine":1,"phoneLine":2,"emailLine":3,"confidence":0.9}]
                educationPointers: [{"schoolLine":1,"degreeLine":2,"timeLine":3,"descriptionLineRange":[1,3],"confidence":0.9}]
                workExperiencePointers: [{"companyLine":10,"positionLine":11,"timeLine":12,"descriptionLineRange":[13,20],"confidence":0.85}]
                projectPointers: [{"nameLine":30,"descriptionLineRange":[31,38],"techStackLines":[32,33],"responsibilityLineRange":[34,38],"confidence":0.82}]
                achievementPointers: [{"titleLine":40,"timeLine":41,"descriptionLineRange":[40,41],"confidence":0.8}]
                summaryPointers: [{"lineRange":[50,52],"confidence":0.8}]
                输入 indexedLines：
                %s
                """.formatted(extractorType, objectMapper.writeValueAsString(lines));
    }

    private void sanitize(
            ResumePointerExtractionResultDTO result,
            List<ResumeIndexedLineDTO> indexedLines,
            ResumePointerExtractorType extractorType) {
        if (result == null) {
            return;
        }
        if (extractorType != ResumePointerExtractorType.BASIC_INFO) {
            result.setBasicInfoPointers(List.of());
        } else {
            result.setBasicInfoPointers((result.getBasicInfoPointers() == null ? List.<ResumePointerExtractionResultDTO.BasicInfoPointer>of() : result.getBasicInfoPointers())
                    .stream()
                    .filter(pointer -> validOptionalLine(pointer.getNameLine(), indexedLines)
                            && validOptionalLine(pointer.getPhoneLine(), indexedLines)
                            && validOptionalLine(pointer.getEmailLine(), indexedLines)
                            && validConfidence(pointer.getConfidence()))
                    .toList());
        }
        if (extractorType != ResumePointerExtractorType.EDUCATION) {
            result.setEducationPointers(List.of());
        } else {
            result.setEducationPointers((result.getEducationPointers() == null ? List.<ResumePointerExtractionResultDTO.EducationPointer>of() : result.getEducationPointers())
                    .stream()
                    .filter(pointer -> validOptionalEntityLine(pointer.getSchoolLine(), indexedLines)
                            && validOptionalEntityLine(pointer.getDegreeLine(), indexedLines)
                            && validOptionalLine(pointer.getTimeLine(), indexedLines)
                            && validOptionalRange(pointer.getDescriptionLineRange(), indexedLines)
                            && validConfidence(pointer.getConfidence()))
                    .toList());
        }
        if (extractorType != ResumePointerExtractorType.WORK_EXPERIENCE) {
            result.setWorkExperiencePointers(List.of());
        } else {
            result.setWorkExperiencePointers((result.getWorkExperiencePointers() == null ? List.<ResumePointerExtractionResultDTO.WorkExperiencePointer>of() : result.getWorkExperiencePointers())
                    .stream()
                    .filter(pointer -> validOptionalEntityLine(pointer.getCompanyLine(), indexedLines)
                            && validOptionalEntityLine(pointer.getPositionLine(), indexedLines)
                            && validOptionalLine(pointer.getTimeLine(), indexedLines)
                            && validOptionalRange(pointer.getDescriptionLineRange(), indexedLines)
                            && validConfidence(pointer.getConfidence()))
                    .toList());
        }
        if (extractorType != ResumePointerExtractorType.PROJECT) {
            result.setProjectPointers(List.of());
        } else {
            result.setProjectPointers((result.getProjectPointers() == null ? List.<ResumePointerExtractionResultDTO.ProjectPointer>of() : result.getProjectPointers())
                    .stream()
                    .filter(pointer -> validOptionalEntityLine(pointer.getNameLine(), indexedLines)
                            && validOptionalRange(pointer.getDescriptionLineRange(), indexedLines)
                            && validOptionalRange(pointer.getResponsibilityLineRange(), indexedLines)
                            && validLineList(pointer.getTechStackLines(), indexedLines)
                            && validConfidence(pointer.getConfidence()))
                    .toList());
        }
        if (extractorType != ResumePointerExtractorType.ACHIEVEMENT) {
            result.setAchievementPointers(List.of());
        } else {
            result.setAchievementPointers((result.getAchievementPointers() == null ? List.<ResumePointerExtractionResultDTO.AchievementPointer>of() : result.getAchievementPointers())
                    .stream()
                    .filter(pointer -> validOptionalEntityLine(pointer.getTitleLine(), indexedLines)
                            && validOptionalLine(pointer.getTimeLine(), indexedLines)
                            && validOptionalRange(pointer.getDescriptionLineRange(), indexedLines)
                            && validConfidence(pointer.getConfidence()))
                    .toList());
        }
        if (extractorType != ResumePointerExtractorType.SUMMARY) {
            result.setSummaryPointers(List.of());
        } else {
            result.setSummaryPointers((result.getSummaryPointers() == null ? List.<ResumePointerExtractionResultDTO.SummaryPointer>of() : result.getSummaryPointers())
                    .stream()
                    .filter(pointer -> validOptionalRange(pointer.getLineRange(), indexedLines)
                            && pointer.getLineRange() != null
                            && validConfidence(pointer.getConfidence()))
                    .toList());
        }
    }

    private boolean validOptionalLine(Integer lineId, List<ResumeIndexedLineDTO> indexedLines) {
        return lineId == null || indexedLines.stream().anyMatch(line -> lineId.equals(line.getLineId()));
    }

    private boolean validOptionalEntityLine(Integer lineId, List<ResumeIndexedLineDTO> indexedLines) {
        if (lineId == null) {
            return true;
        }
        return indexedLines.stream()
                .filter(line -> lineId.equals(line.getLineId()))
                .findFirst()
                .filter(line -> !Boolean.TRUE.equals(line.getIsNoise()))
                .filter(line -> !isFieldLabel(line.getNormalizedText()))
                .isPresent();
    }

    private boolean validOptionalRange(List<Integer> range, List<ResumeIndexedLineDTO> indexedLines) {
        if (range == null || range.isEmpty()) {
            return true;
        }
        if (range.size() != 2 || range.get(0) == null || range.get(1) == null || range.get(0) > range.get(1)) {
            return false;
        }
        return validOptionalLine(range.get(0), indexedLines) && validOptionalLine(range.get(1), indexedLines);
    }

    private boolean validLineList(List<Integer> lineIds, List<ResumeIndexedLineDTO> indexedLines) {
        return lineIds == null || lineIds.stream().allMatch(lineId -> validOptionalLine(lineId, indexedLines));
    }

    private boolean validConfidence(Double confidence) {
        return confidence == null || (confidence >= 0.0 && confidence <= 1.0);
    }

    private boolean isFieldLabel(String value) {
        if (value == null) {
            return true;
        }
        return value.strip().matches("^(公司名称|职位名称|工作时间|工作描述|项目名称|项目描述|开发环境|技术选型|毕业院校|学历|专业|姓名|电话|邮箱|未识别)[:：]?$");
    }

    private String cacheKey(
            Long userId,
            Long resumeId,
            List<ResumeIndexedLineDTO> indexedLines,
            ResumeParseMode parseMode,
            ResumePointerExtractorType extractorType,
            AiSelectionSnapshot selection) {
        return "pointer"
                + ":userId=" + (userId == null ? "unknown" : userId)
                + ":resumeId=" + (resumeId == null ? "unknown" : resumeId)
                + ":selection=" + (selection == null ? "legacy-system" : selection.cacheIdentity(userId))
                + ":indexedLinesHash=" + sha256(indexedLines.stream().map(line -> line.getLineId() + ":" + line.getNormalizedText()).reduce("", (left, right) -> left + "\n" + right))
                + ":parseMode=" + parseMode
                + ":extractorType=" + extractorType
                + ":promptVersion=" + PROMPT_VERSION
                + ":parserVersion=" + ResumeParseVersions.PARSER_VERSION;
    }

    private String extractJson(String value) {
        if (value == null) {
            return "{}";
        }
        String stripped = value.strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return stripped.substring(start, end + 1);
        }
        return "{}";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private ResumePointerExtractionResultDTO copy(ResumePointerExtractionResultDTO result) {
        return objectMapper.convertValue(result, ResumePointerExtractionResultDTO.class);
    }
}
