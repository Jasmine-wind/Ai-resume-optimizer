package com.winter.airesumeoptimizer.module.resume.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayRequest;
import com.winter.airesumeoptimizer.infra.ai.AiCompletionResult;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGateway;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewaySupport;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.infra.cache.RedisJsonCacheService;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeAchievementDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeDisplayModelDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeExperienceDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeProjectDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeRawSectionBlockDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeRawSectionDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeSkillSetDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeSourceRefDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeStructuredContentDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeStructuredDataDTO;
import com.winter.airesumeoptimizer.module.resume.service.ResumeDisplayModelService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ResumeDisplayModelServiceImpl implements ResumeDisplayModelService {

    private static final Logger log = LoggerFactory.getLogger(ResumeDisplayModelServiceImpl.class);
    private static final String DISPLAY_PROMPT_VERSION = "resume-display-model-v2.9.19.2";
    private static final String DISPLAY_ADAPTER_VERSION = "resume-display-adapter-v2.9.19.2";
    private static final int MAX_PROMPT_OTHERS = 8;
    private static final int MAX_RAW_SECTION_SUMMARIES = 10;
    private static final int MAX_TEXT_LENGTH = 180;
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(?:19|20)\\d{2}(?:\\s*[./年-]\\s*\\d{1,2}\\s*月?)?\\s*(?:[-~—–至到]+)\\s*(?:(?:19|20)\\d{2}(?:\\s*[./年-]\\s*\\d{1,2}\\s*月?)?|至今|Present)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAIL_LABEL_PREFIX_PATTERN = Pattern.compile(
            "^(项目名称|项目描述|技术栈|技术选型|开发环境|开发工具|软件构架|软件架构|负责模块|参与项目描述|职责|工作内容|公司名称|职位名称|工作时间|工作描述)[:：]?",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> SKILL_GROUP_LABELS = Map.ofEntries(
            Map.entry("language", "后端"),
            Map.entry("framework", "后端"),
            Map.entry("backend", "后端"),
            Map.entry("frontend", "前端"),
            Map.entry("database", "数据库"),
            Map.entry("middleware", "中间件"),
            Map.entry("tool", "工具 / 环境"),
            Map.entry("cv", "AI / 算法"),
            Map.entry("ai", "AI / 算法"),
            Map.entry("data", "AI / 算法"),
            Map.entry("other", "其他技能"));

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResumeDisplayModelDTO> cache = new ConcurrentHashMap<>();
    private final RedisJsonCacheService redisJsonCacheService;

    public ResumeDisplayModelServiceImpl(AiGateway aiGateway, ObjectMapper objectMapper) {
        this(aiGateway, objectMapper, null);
    }

    @Autowired
    public ResumeDisplayModelServiceImpl(
            AiGateway aiGateway,
            ObjectMapper objectMapper,
            RedisJsonCacheService redisJsonCacheService) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.redisJsonCacheService = redisJsonCacheService;
    }

    @Override
    public ResumeDisplayModelDTO buildRuleDisplayModel(Long resumeId, ResumeStructuredContentDTO structuredContent) {
        ResumeStructuredDataDTO data = structuredContent == null ? null : structuredContent.getStructuredData();
        ResumeDisplayModelDTO model = ResumeDisplayModelDTO.builder()
                .overview(buildOverview(structuredContent, data))
                .skillSummary(buildSkillSummary(data == null ? null : data.getSkills()))
                .educationCards(buildEducationCards(structuredContent, data))
                .workExperienceCards(buildExperienceCards(data, "WORK"))
                .internshipCards(buildExperienceCards(data, "INTERNSHIP"))
                .campusExperienceCards(buildCampusCards(data))
                .projectCards(buildProjectCards(data))
                .achievementCards(buildAchievementCards(data))
                .certificateTags(unique(data == null ? structuredContent == null ? List.of() : structuredContent.getCertificates() : data.getCertificates()).stream()
                        .filter(this::isUsefulValue)
                        .toList())
                .summaryCard(buildSummaryCard(data == null ? structuredContent == null ? null : structuredContent.getSummary() : data.getSummary(),
                        data == null ? null : data.getSummarySourceRef()))
                .pendingItems(buildPendingItems(data == null ? structuredContent == null ? List.of() : structuredContent.getOthers() : data.getOthers()))
                .displayMeta(ResumeDisplayModelDTO.DisplayMeta.builder()
                        .generatedBy("RULE")
                        .aiDisplayUsed(false)
                        .aiDisplayFallback(false)
                        .aiDisplayErrorMessage("")
                        .aiDisplayDurationMs(0L)
                        .cacheHit(false)
                        .cacheKeyDigest("")
                        .displayPromptVersion(DISPLAY_PROMPT_VERSION)
                        .displayAdapterVersion(DISPLAY_ADAPTER_VERSION)
                        .modelName(modelName())
                        .build())
                .build();
        normalizeModel(model);
        return model;
    }

    @Override
    public ResumeDisplayModelDTO buildAiDisplayModel(Long resumeId, ResumeStructuredContentDTO structuredContent) {
        return buildAiDisplayModel(null, resumeId, structuredContent, null);
    }

    @Override
    public ResumeDisplayModelDTO buildAiDisplayModel(
            Long resumeId,
            ResumeStructuredContentDTO structuredContent,
            AiSelectionSnapshot selection) {
        return buildAiDisplayModel(null, resumeId, structuredContent, selection);
    }

    public ResumeDisplayModelDTO buildAiDisplayModel(
            Long userId,
            Long resumeId,
            ResumeStructuredContentDTO structuredContent,
            AiSelectionSnapshot selection) {
        long startedAt = System.nanoTime();
        ResumeDisplayModelDTO ruleModel = buildRuleDisplayModel(resumeId, structuredContent);
        if (selection == null && userId != null) {
            try {
                selection = AiGatewaySupport.selectionForNewTask(
                        aiGateway, userId, "RESUME_DISPLAY_MODEL_SELECTION");
            } catch (AiGatewayException exception) {
                if (exception.getFailureCode() == AiFailureCode.AI_CONFIGURATION_REQUIRED) {
                    applyDisplayMeta(ruleModel, "RULE", false, true, "AI_CONFIGURATION_REQUIRED",
                            elapsedMs(startedAt), false, "", null);
                    return ruleModel;
                }
                throw exception;
            }
        }
        try {
            Map<String, Object> promptInput = buildPromptInput(structuredContent);
            String cacheKey = buildCacheKey(userId, resumeId, promptInput, selection);
            ResumeDisplayModelDTO cached = getCachedDisplayModel(cacheKey);
            if (cached != null) {
                ResumeDisplayModelDTO cachedCopy = copy(cached);
                applyDisplayMeta(cachedCopy, "AI", true, false, "", elapsedMs(startedAt), true, cacheKey, selection);
                return cachedCopy;
            }
            String prompt = buildPrompt(promptInput);
            AiCompletionResult completion = AiGatewaySupport.complete(
                    aiGateway,
                    new AiInvocationContext(userId, null, "RESUME_DISPLAY_MODEL", selection),
                    displayRequest(prompt));
            ResumeDisplayModelDTO aiModel = readDisplayModel(completion.text());
            ResumeDisplayModelDTO validated = validateAiModel(aiModel, ruleModel, structuredContent);
            applyDisplayMeta(validated, "AI", true, false, "", elapsedMs(startedAt), false, cacheKey, selection);
            putCachedDisplayModel(cacheKey, validated);
            return validated;
        } catch (RuntimeException exception) {
            if (selection != null && selection.isUserByok()) {
                if (exception instanceof AiGatewayException gatewayException) {
                    throw gatewayException;
                }
                throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI 展示模型结果格式异常");
            }
            // Parser/provider messages can contain echoed resume content.
            log.warn("Resume AI display model fallback: resumeId={}, exceptionType={}",
                    resumeId,
                    exception.getClass().getSimpleName());
            ResumeDisplayModelDTO fallback = copy(ruleModel);
            applyDisplayMeta(fallback, "RULE", false, true, safeError(exception.getMessage()), elapsedMs(startedAt), false, "", selection);
            return fallback;
        }
    }

    @Override
    public ResumeDisplayModelDTO getCachedAiDisplayModel(Long resumeId, ResumeStructuredContentDTO structuredContent) {
        return getCachedAiDisplayModel(null, resumeId, structuredContent, null);
    }

    @Override
    public ResumeDisplayModelDTO getCachedAiDisplayModel(
            Long resumeId,
            ResumeStructuredContentDTO structuredContent,
            AiSelectionSnapshot selection) {
        return getCachedAiDisplayModel(null, resumeId, structuredContent, selection);
    }

    @Override
    public ResumeDisplayModelDTO getCachedAiDisplayModel(
            Long userId,
            Long resumeId,
            ResumeStructuredContentDTO structuredContent,
            AiSelectionSnapshot selection) {
        long startedAt = System.nanoTime();
        if (selection == null && userId != null) {
            try {
                selection = AiGatewaySupport.selectionForNewTask(
                        aiGateway, userId, "RESUME_DISPLAY_MODEL_CACHE_SELECTION");
            } catch (AiGatewayException exception) {
                if (exception.getFailureCode() == AiFailureCode.AI_CONFIGURATION_REQUIRED) {
                    return null;
                }
                throw exception;
            }
        }
        try {
            Map<String, Object> promptInput = buildPromptInput(structuredContent);
            String cacheKey = buildCacheKey(userId, resumeId, promptInput, selection);
            ResumeDisplayModelDTO cached = getCachedDisplayModel(cacheKey);
            if (cached == null) {
                return null;
            }
            ResumeDisplayModelDTO cachedCopy = copy(cached);
            applyDisplayMeta(cachedCopy, "AI", true, false, "", elapsedMs(startedAt), true, cacheKey, selection);
            return cachedCopy;
        } catch (RuntimeException exception) {
            log.warn("Resume cached AI display model ignored: resumeId={}, exceptionType={}",
                    resumeId,
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private ResumeDisplayModelDTO getCachedDisplayModel(String cacheKey) {
        ResumeDisplayModelDTO cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (redisJsonCacheService == null) {
            return null;
        }
        return redisJsonCacheService.get(cacheKey, ResumeDisplayModelDTO.class)
                .map(cachedModel -> {
                    cache.put(cacheKey, copy(cachedModel));
                    return cachedModel;
                })
                .orElse(null);
    }

    private void putCachedDisplayModel(String cacheKey, ResumeDisplayModelDTO model) {
        ResumeDisplayModelDTO cachedCopy = copy(model);
        cache.put(cacheKey, cachedCopy);
        if (redisJsonCacheService != null) {
            redisJsonCacheService.put(cacheKey, cachedCopy, redisJsonCacheService.aiDisplayModelTtlSeconds());
        }
    }

    private ResumeDisplayModelDTO.Overview buildOverview(ResumeStructuredContentDTO content, ResumeStructuredDataDTO data) {
        Map<String, String> basicInfo = content == null || content.getBasicInfo() == null ? Map.of() : content.getBasicInfo();
        List<String> skills = skills(data);
        return ResumeDisplayModelDTO.Overview.builder()
                .name(validName(firstNonBlank(content == null ? null : content.getName(), basicInfo.get("name"))) ? firstNonBlank(content.getName(), basicInfo.get("name")) : "")
                .targetRole(firstNonBlank(content == null ? null : content.getJobIntention(), basicInfo.get("jobIntention")))
                .resumeType(firstNonBlank(content == null ? null : content.getResumeType(), basicInfo.get("resumeType")))
                .highestDegree(firstNonBlank(content == null ? null : content.getHighestEducation(), basicInfo.get("degree"), basicInfo.get("highestEducation")))
                .workYears(firstNonBlank(basicInfo.get("workYears"), basicInfo.get("workExperience")))
                .coreSkills(skills.stream().limit(10).toList())
                .build();
    }

    private ResumeDisplayModelDTO.SkillSummary buildSkillSummary(ResumeSkillSetDTO skills) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String label : List.of("后端", "前端", "数据库", "中间件", "工具 / 环境", "AI / 算法", "其他技能")) {
            grouped.put(label, new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> entry : skills == null || skills.getGroups() == null ? Map.<String, List<String>>of().entrySet() : skills.getGroups().entrySet()) {
            String label = SKILL_GROUP_LABELS.getOrDefault(entry.getKey(), "其他技能");
            grouped.get(label).addAll(entry.getValue() == null ? List.of() : entry.getValue());
        }
        for (String skill : skills(skills)) {
            String label = inferSkillGroupLabel(skill);
            grouped.get(label).add(skill);
        }
        List<ResumeDisplayModelDTO.SkillGroup> groups = grouped.entrySet().stream()
                .map(entry -> ResumeDisplayModelDTO.SkillGroup.builder()
                        .name(entry.getKey())
                        .skills(unique(entry.getValue()).stream().filter(this::isSkillTag).limit(20).toList())
                        .build())
                .filter(group -> group.getSkills() != null && !group.getSkills().isEmpty())
                .toList();
        List<String> topSkills = groups.stream().flatMap(group -> group.getSkills().stream()).distinct().limit(10).toList();
        return ResumeDisplayModelDTO.SkillSummary.builder()
                .topSkills(topSkills)
                .groups(groups)
                .build();
    }

    private List<ResumeDisplayModelDTO.EducationCard> buildEducationCards(ResumeStructuredContentDTO content, ResumeStructuredDataDTO data) {
        Map<String, String> basicInfo = content == null || content.getBasicInfo() == null ? Map.of() : content.getBasicInfo();
        List<String> lines = unique(data == null ? content == null ? List.of() : content.getEducation() : data.getEducation()).stream()
                .filter(this::isUsefulValue)
                .toList();
        if (lines.isEmpty() && firstNonBlank(basicInfo.get("university"), basicInfo.get("school")).isBlank()) {
            return List.of();
        }
        String joined = String.join(" ", lines);
        return List.of(ResumeDisplayModelDTO.EducationCard.builder()
                .school(firstNonBlank(basicInfo.get("university"), basicInfo.get("school"), match(joined, "[\\u4e00-\\u9fa5]{2,}(?:大学|学院|学校|职业学院|工学院)")))
                .degree(firstNonBlank(basicInfo.get("degree"), match(joined, "博士|硕士|研究生|本科|大专|专科|高中")))
                .major(firstNonBlank(basicInfo.get("major"), ""))
                .timeRange(firstNonBlank(matchDateRange(joined), basicInfo.get("graduationDate")))
                .summary(lines.stream().filter(line -> !isLabelOnly(line)).limit(2).reduce((left, right) -> left + "；" + right).orElse(""))
                .sourceRef(data == null || data.getEducationSourceRefs() == null || data.getEducationSourceRefs().isEmpty() ? null : data.getEducationSourceRefs().get(0))
                .build());
    }

    private List<ResumeDisplayModelDTO.ExperienceCard> buildExperienceCards(ResumeStructuredDataDTO data, String type) {
        List<ResumeExperienceDTO> experiences = data == null || data.getExperiences() == null ? List.of() : data.getExperiences();
        List<ResumeExperienceDTO> matched = experiences.stream()
                .filter(item -> item != null && type.equals(item.getType()))
                .toList();
        if (matched.isEmpty()) {
            return List.of();
        }
        Map<String, List<ResumeExperienceDTO>> groups = new LinkedHashMap<>();
        for (ResumeExperienceDTO item : matched) {
            String joined = String.join(" ", collectExperienceLines(item));
            String key = firstNonBlank(
                    item.getSourceSectionId(),
                    item.getOrganization(),
                    extractOrganization(joined),
                    type);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        List<ResumeDisplayModelDTO.ExperienceCard> cards = new ArrayList<>();
        int index = 0;
        for (List<ResumeExperienceDTO> items : groups.values()) {
            ResumeDisplayModelDTO.ExperienceCard card = toExperienceCard(items, type, index++);
            if (isUsefulValue(card.getCompany()) || isUsefulValue(card.getSummary()) || !empty(card.getResponsibilities())) {
                cards.add(card);
            }
        }
        return cards;
    }

    private ResumeDisplayModelDTO.ExperienceCard toExperienceCard(List<ResumeExperienceDTO> items, String type, int index) {
        List<String> lines = unique(items.stream()
                .flatMap(item -> collectExperienceLines(item).stream())
                .toList());
        String joined = String.join(" ", lines);
        String company = cleanInvalidDisplay(firstNonBlank(
                items.stream().map(ResumeExperienceDTO::getOrganization).filter(this::isUsefulValue).findFirst().orElse(null),
                extractOrganization(joined),
                "WORK".equals(type) ? "工作经历 " + (index + 1) : "经历 " + (index + 1)));
        if (company.isBlank()) {
            company = "WORK".equals(type) ? "工作经历 " + (index + 1) : "经历 " + (index + 1);
        }
        String timeRange = firstNonBlank(
                items.stream().map(item -> formatDateRange(item.getStartDate(), item.getEndDate())).filter(this::isUsefulValue).findFirst().orElse(null),
                matchDateRange(joined));
        String position = cleanInvalidDisplay(firstNonBlank(
                items.stream().map(ResumeExperienceDTO::getRole).filter(this::isUsefulValue).findFirst().orElse(null),
                extractRole(joined)));
        List<String> responsibilities = lines.stream()
                .map(this::shorten)
                .filter(this::isUsefulValue)
                .filter(line -> !isLabelOnly(line))
                .toList();
        final String companyForFilter = company;
        final String positionForFilter = position;
        final String timeRangeForFilter = timeRange;
        responsibilities = responsibilities.stream()
                .filter(line -> !duplicates(line, companyForFilter, positionForFilter, timeRangeForFilter))
                .filter(line -> !isLowQualityDetail(line, List.of(), companyForFilter, positionForFilter, timeRangeForFilter))
                .limit(8)
                .toList();
        String summary = responsibilities.stream().findFirst().orElse("");
        List<String> compactResponsibilities = responsibilities.stream()
                .filter(line -> !duplicates(line, summary))
                .toList();
        return ResumeDisplayModelDTO.ExperienceCard.builder()
                .company(company)
                .position(List.of(position, timeRange).stream().filter(this::isUsefulValue).reduce((left, right) -> left + " · " + right).orElse(""))
                .timeRange("")
                .summary(summary)
                .responsibilities(compactResponsibilities)
                .collapsed(true)
                .sourceRef(mergeSourceRefs(items.stream().map(ResumeExperienceDTO::getSourceRef).toList()))
                .build();
    }

    private List<ResumeDisplayModelDTO.ExperienceCard> buildCampusCards(ResumeStructuredDataDTO data) {
        List<ResumeExperienceDTO> experiences = data == null || data.getExperiences() == null ? List.of() : data.getExperiences();
        List<ResumeExperienceDTO> campus = experiences.stream()
                .filter(item -> item != null && List.of("CAMPUS", "PRACTICE", "VOLUNTEER").contains(item.getType()))
                .toList();
        if (campus.isEmpty()) {
            return List.of();
        }
        List<String> bullets = unique(campus.stream()
                .flatMap(item -> collectExperienceLines(item).stream())
                .filter(this::isUsefulValue)
                .toList());
        return List.of(ResumeDisplayModelDTO.ExperienceCard.builder()
                .company("校园 / 实践经历")
                .position("")
                .timeRange("")
                .summary(bullets.stream().findFirst().orElse(""))
                .responsibilities(bullets)
                .collapsed(true)
                .build());
    }

    private List<ResumeDisplayModelDTO.ProjectCard> buildProjectCards(ResumeStructuredDataDTO data) {
        List<ResumeProjectDTO> projects = ProjectSourceTextExtractor.expandProjects(data == null ? List.of() : data.getProjects());
        List<ResumeDisplayModelDTO.ProjectCard> cards = new ArrayList<>();
        Map<String, List<ResumeProjectDTO>> groups = new LinkedHashMap<>();
        for (ResumeProjectDTO project : projects) {
            if (project == null) {
                continue;
            }
            String key = firstNonBlank(
                    cleanProjectName(project.getName()),
                    project.getSourceRef() == null ? null : project.getSourceRef().getStartLine() + "-" + project.getSourceRef().getEndLine(),
                    project.getParentExperienceIndex() == null ? null : "parent-" + project.getParentExperienceIndex(),
                    "project-" + groups.size());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(project);
        }
        int index = 0;
        for (List<ResumeProjectDTO> items : groups.values()) {
            ResumeProjectDTO primary = items.get(0);
            List<String> evidence = unique(items.stream().flatMap(project -> (project.getEvidence() == null ? List.<String>of() : project.getEvidence()).stream()).toList());
            List<String> responsibilities = unique(items.stream()
                    .flatMap(project -> {
                        List<String> values = project.getResponsibilities() == null || project.getResponsibilities().isEmpty()
                                ? project.getEvidence()
                                : project.getResponsibilities();
                        return (values == null ? List.<String>of() : values).stream();
                    })
                    .toList()).stream().filter(this::isUsefulValue).limit(8).toList();
            String name = firstNonBlank(cleanProjectName(primary.getName()), cleanProjectName(primary.getDescription()), "项目经历");
            String summary = shorten(firstNonBlank(
                    usefulProjectSummary(primary.getDescription(), name, responsibilities, evidence),
                    evidence.stream().map(line -> usefulProjectSummary(line, name, responsibilities, evidence)).filter(this::isUsefulValue).findFirst().orElse("")));
            if (duplicates(summary, name)) {
                summary = responsibilities.stream().filter(line -> !duplicates(line, name)).findFirst().orElse("");
            }
            List<String> techStack = unique(items.stream().flatMap(project -> (project.getTechStack() == null ? List.<String>of() : project.getTechStack()).stream()).toList())
                    .stream().filter(this::isSkillTag).limit(12).toList();
            final String summaryForFilter = summary;
            ResumeDisplayModelDTO.ProjectCard card = ResumeDisplayModelDTO.ProjectCard.builder()
                    .name(name)
                    .summary(summary)
                    .techStack(techStack)
                    .responsibilities(responsibilities.stream()
                            .filter(line -> !duplicates(line, summaryForFilter, name))
                            .filter(line -> !isLowQualityDetail(line, techStack, summaryForFilter, name))
                            .toList())
                    .collapsed(true)
                    .sourceRef(mergeSourceRefs(items.stream().map(ResumeProjectDTO::getSourceRef).toList()))
                    .build();
            if (shouldDisplayProjectCard(card)) {
                cards.add(card);
            }
            index++;
        }
        return cards;
    }

    private boolean shouldDisplayProjectCard(ResumeDisplayModelDTO.ProjectCard card) {
        if (card == null) {
            return false;
        }
        boolean reliableTitle = isUsefulValue(cleanProjectName(card.getName())) && !"项目经历".equals(cleanProjectName(card.getName()));
        boolean hasSummary = isUsefulValue(card.getSummary());
        boolean hasResponsibilities = card.getResponsibilities() != null && !card.getResponsibilities().isEmpty();
        boolean hasTechStack = card.getTechStack() != null && !card.getTechStack().isEmpty();
        if (reliableTitle) {
            return hasSummary || hasResponsibilities || hasTechStack;
        }
        return hasSummary && hasResponsibilities;
    }

    private String usefulProjectSummary(String value, String name, List<String> responsibilities, List<String> evidence) {
        String cleaned = cleanInvalidDisplay(value);
        if (!isUsefulValue(cleaned)
                || isLabelOnly(cleaned)
                || DETAIL_LABEL_PREFIX_PATTERN.matcher(cleaned).find()
                || isSkillFragmentText(cleaned)
                || duplicates(cleaned, name)
                || (responsibilities != null && responsibilities.stream().anyMatch(item -> duplicates(cleaned, item)))) {
            return "";
        }
        if (evidence != null && !evidence.isEmpty() && cleaned.length() > 12) {
            String normalized = normalizeDisplay(cleaned);
            boolean hasEvidence = evidence.stream().anyMatch(item -> normalizeDisplay(item).contains(normalized) || normalized.contains(normalizeDisplay(item)));
            if (!hasEvidence && value != null && value.length() > 40) {
                return "";
            }
        }
        return shorten(cleaned);
    }

    private List<ResumeDisplayModelDTO.AchievementCard> buildAchievementCards(ResumeStructuredDataDTO data) {
        List<ResumeAchievementDTO> achievements = data == null || data.getAchievements() == null ? List.of() : data.getAchievements();
        return achievements.stream()
                .filter(Objects::nonNull)
                .map(item -> ResumeDisplayModelDTO.AchievementCard.builder()
                        .title(cleanInvalidDisplay(firstNonBlank(item.getTitle(), item.getEvidence() == null ? null : item.getEvidence().stream().findFirst().orElse(null))))
                        .meta(List.of(item.getLevel(), item.getCompetition(), item.getRanking(), firstNonBlank(item.getTimeRange(), item.getDate()))
                                .stream().filter(this::isUsefulValue).distinct().reduce((left, right) -> left + " · " + right).orElse(""))
                        .sourceRef(item.getSourceRef())
                        .build())
                .filter(card -> isUsefulValue(card.getTitle()))
                .toList();
    }

    private ResumeDisplayModelDTO.SummaryCard buildSummaryCard(String summary, ResumeSourceRefDTO sourceRef) {
        if (!isUsefulValue(summary) || isSkillFragmentText(summary)) {
            return null;
        }
        return ResumeDisplayModelDTO.SummaryCard.builder()
                .content(shorten(summary))
                .collapsed(true)
                .sourceRef(sourceRef)
                .build();
    }

    private List<String> buildPendingItems(List<String> others) {
        return unique(others).stream()
                .map(this::shorten)
                .filter(this::isUsefulValue)
                .filter(item -> !isLabelOnly(item))
                .limit(20)
                .toList();
    }

    private Map<String, Object> buildPromptInput(ResumeStructuredContentDTO content) {
        ResumeStructuredDataDTO data = content == null ? null : content.getStructuredData();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("basicInfo", content == null ? Map.of() : content.getBasicInfo());
        input.put("education", data == null ? List.of() : data.getEducation());
        input.put("skills", data == null ? null : data.getSkills());
        input.put("experiences", data == null ? List.of() : data.getExperiences());
        input.put("projects", data == null ? List.of() : data.getProjects());
        input.put("achievements", data == null ? List.of() : data.getAchievements());
        input.put("certificates", data == null ? List.of() : data.getCertificates());
        input.put("summary", data == null ? "" : data.getSummary());
        input.put("others", unique(data == null ? List.of() : data.getOthers()).stream().limit(MAX_PROMPT_OTHERS).toList());
        input.put("rawSections", rawSectionSummaries(content == null ? List.of() : content.getRawSections()));
        input.put("parseMeta", content == null ? null : content.getParseMeta());
        input.put("qualityWarnings", content == null ? List.of() : content.getQualityWarnings());
        input.put("ruleDisplayModel", buildRuleDisplayModel(null, content));
        return input;
    }

    private List<Map<String, Object>> rawSectionSummaries(List<ResumeRawSectionDTO> rawSections) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ResumeRawSectionDTO section : rawSections == null ? List.<ResumeRawSectionDTO>of() : rawSections) {
            if (section == null || summaries.size() >= MAX_RAW_SECTION_SUMMARIES) {
                continue;
            }
            List<String> samples = section.getBlocks() == null ? List.of() : section.getBlocks().stream()
                    .filter(Objects::nonNull)
                    .map(ResumeRawSectionBlockDTO::getText)
                    .filter(this::isUsefulValue)
                    .map(this::shorten)
                    .limit(2)
                    .toList();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", section.getId());
            summary.put("originalTitle", section.getOriginalTitle());
            summary.put("normalizedSection", section.getNormalizedSection());
            summary.put("displayName", section.getDisplayName());
            summary.put("sampleLines", samples);
            summaries.add(summary);
        }
        return summaries;
    }

    private String buildPrompt(Map<String, Object> promptInput) {
        try {
            return """
                    你是简历解析结果展示优化助手。

                    你的任务不是重新解析简历，也不是决定卡片边界，而是润色已有 ruleDisplayModel 的展示文案。
                    你只能使用输入中已经存在的信息，不得编造任何事实。

                    请完成：
                    1. 只优化 summary、responsibilities(details)、summaryCard.content 和 pendingItems 文案。
                    2. 不要新增、删除、拆分或合并卡片。
                    3. 不要修改公司、职位、时间、学校、项目名、技术栈、证书、奖项标题。
                    4. 不要新增输入中不存在的事实。
                    5. 不要在主卡片中展示“未识别”“公司未识别”等影响观感的文字。
                    6. summary 是用户可读摘要；responsibilities 是结构化详情；sourceRef/sourceText 是原文依据，不要拆成默认 details。
                    7. 不要把 sourceText、rawText、evidence、debug 复制进 summary 或 responsibilities。
                    8. 不要把 skills 填到 summaryCard.content；自我评价只能来自 summary/summaryCard 原文。
                    9. project responsibilities 不要只返回技术词或字段标签；如果详情质量低，返回空数组。
                    10. 技术词只放 techStack/skillSummary，不要放 responsibilities。
                    11. 长内容需要压缩为摘要。
                    12. 输出必须是 JSON，不要输出解释，不要输出 Markdown。
                    13. 不要新增输入中不存在的公司、职位、学校、项目、技能、时间、奖项。
                    14. 字段缺失时留空，不要猜测；如果内容无法可靠整理，放入 pendingItems。

                    输出 JSON 必须符合结构：
                    {
                      "overview": {"name":"","targetRole":"","resumeType":"","highestDegree":"","workYears":"","coreSkills":[]},
                      "skillSummary": {"topSkills":[],"groups":[{"name":"后端","skills":[]}]},
                      "educationCards": [{"school":"","degree":"","major":"","timeRange":"","summary":""}],
                      "workExperienceCards": [{"company":"","position":"","timeRange":"","summary":"","responsibilities":[],"collapsed":true}],
                      "internshipCards": [],
                      "campusExperienceCards": [],
                      "projectCards": [{"name":"","summary":"","techStack":[],"responsibilities":[],"collapsed":true}],
                      "achievementCards": [{"title":"","meta":""}],
                      "certificateTags": [],
                      "summaryCard": {"content":"","collapsed":true},
                      "pendingItems": [],
                      "displayMeta": {"generatedBy":"AI","aiDisplayUsed":true,"aiDisplayFallback":false,"aiDisplayErrorMessage":"","aiDisplayDurationMs":0,"cacheHit":false}
                    }

                    输入：
                    %s
                    """.formatted(objectMapper.writeValueAsString(promptInput));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 展示优化 Prompt 输入序列化失败", exception);
        }
    }

    private ResumeDisplayModelDTO readDisplayModel(String aiOutput) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(aiOutput));
            JsonNode modelNode = root.path("displayModel").isObject() ? root.path("displayModel") : root;
            return objectMapper.treeToValue(modelNode, ResumeDisplayModelDTO.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI 展示优化 JSON 解析失败：" + exception.getOriginalMessage(), exception);
        }
    }

    private ResumeDisplayModelDTO validateAiModel(
            ResumeDisplayModelDTO aiModel,
            ResumeDisplayModelDTO ruleModel,
            ResumeStructuredContentDTO content) {
        if (aiModel == null || aiModel.getOverview() == null || aiModel.getSkillSummary() == null) {
            throw new IllegalArgumentException("AI 展示优化结果缺少必要字段");
        }
        Set<String> evidence = collectEvidence(content);
        Set<String> allowedSkills = new LinkedHashSet<>(skills(content == null ? null : content.getStructuredData()));
        ResumeDisplayModelDTO validated = copy(aiModel);
        normalizeModel(validated);
        validateOverview(validated, ruleModel, evidence, allowedSkills);
        validateEducation(validated, ruleModel, evidence);
        validateExperiences(validated, ruleModel, evidence);
        validateProjects(validated, ruleModel, evidence, allowedSkills);
        validateAchievements(validated, ruleModel, evidence);
        validateSummary(validated, ruleModel);
        filterPending(validated);
        return validated;
    }

    private void validateOverview(
            ResumeDisplayModelDTO model,
            ResumeDisplayModelDTO ruleModel,
            Set<String> evidence,
            Set<String> allowedSkills) {
        ResumeDisplayModelDTO.Overview overview = model.getOverview();
        ResumeDisplayModelDTO.Overview rule = ruleModel.getOverview();
        if (!validName(overview.getName()) || !hasEvidence(overview.getName(), evidence)) {
            overview.setName(rule == null ? "" : rule.getName());
        }
        overview.setCoreSkills(unique(overview.getCoreSkills()).stream()
                .filter(allowedSkills::contains)
                .limit(10)
                .toList());
        if (overview.getCoreSkills().isEmpty() && rule != null) {
            overview.setCoreSkills(rule.getCoreSkills());
        }
    }

    private void validateEducation(ResumeDisplayModelDTO model, ResumeDisplayModelDTO ruleModel, Set<String> evidence) {
        List<ResumeDisplayModelDTO.EducationCard> rules = ruleModel.getEducationCards() == null ? List.of() : ruleModel.getEducationCards();
        List<ResumeDisplayModelDTO.EducationCard> cards = new ArrayList<>(model.getEducationCards() == null ? List.of() : model.getEducationCards());
        for (int index = 0; index < cards.size(); index++) {
            ResumeDisplayModelDTO.EducationCard card = cards.get(index);
            ResumeDisplayModelDTO.EducationCard rule = index < rules.size() ? rules.get(index) : null;
            if (!isUsefulValue(card.getSchool()) || !hasEvidence(card.getSchool(), evidence)) {
                card.setSchool(rule == null ? "" : rule.getSchool());
            }
            if (!isUsefulValue(card.getTimeRange()) || (!hasEvidence(card.getTimeRange(), evidence) && !DATE_RANGE_PATTERN.matcher(card.getTimeRange()).find())) {
                card.setTimeRange(rule == null ? "" : rule.getTimeRange());
            }
            if (card.getSourceRef() == null && rule != null) {
                card.setSourceRef(rule.getSourceRef());
            }
        }
        model.setEducationCards(cards.stream().filter(card -> isUsefulValue(card.getSchool()) || isUsefulValue(card.getSummary())).toList());
    }

    private void validateExperiences(ResumeDisplayModelDTO model, ResumeDisplayModelDTO ruleModel, Set<String> evidence) {
        model.setWorkExperienceCards(polishExperienceList(model.getWorkExperienceCards(), ruleModel.getWorkExperienceCards()));
        model.setInternshipCards(polishExperienceList(model.getInternshipCards(), ruleModel.getInternshipCards()));
        model.setCampusExperienceCards(polishExperienceList(model.getCampusExperienceCards(), ruleModel.getCampusExperienceCards()));
    }

    private List<ResumeDisplayModelDTO.ExperienceCard> polishExperienceList(
            List<ResumeDisplayModelDTO.ExperienceCard> cards,
            List<ResumeDisplayModelDTO.ExperienceCard> rules) {
        List<ResumeDisplayModelDTO.ExperienceCard> result = new ArrayList<>();
        List<ResumeDisplayModelDTO.ExperienceCard> ruleCards = rules == null ? List.of() : rules;
        List<ResumeDisplayModelDTO.ExperienceCard> aiCards = cards == null ? List.of() : cards;
        for (int index = 0; index < ruleCards.size(); index++) {
            ResumeDisplayModelDTO.ExperienceCard rule = objectMapper.convertValue(ruleCards.get(index), ResumeDisplayModelDTO.ExperienceCard.class);
            ResumeDisplayModelDTO.ExperienceCard polished = index < aiCards.size() ? aiCards.get(index) : null;
            if (polished != null) {
                String summary = cleanInvalidDisplay(polished.getSummary());
                if (isUsefulValue(summary)) {
                    rule.setSummary(summary);
                }
                List<String> responsibilities = unique(polished.getResponsibilities()).stream()
                        .filter(this::isUsefulValue)
                        .filter(item -> !isForbiddenDisplay(item))
                        .filter(item -> !isLowQualityDetail(item, List.of(), rule.getSummary(), rule.getCompany(), rule.getPosition(), rule.getTimeRange()))
                        .limit(8)
                        .toList();
                if (!responsibilities.isEmpty()) {
                    rule.setResponsibilities(responsibilities);
                }
            }
            result.add(rule);
        }
        return result;
    }

    private void validateProjects(
            ResumeDisplayModelDTO model,
            ResumeDisplayModelDTO ruleModel,
            Set<String> evidence,
            Set<String> allowedSkills) {
        List<ResumeDisplayModelDTO.ProjectCard> rules = ruleModel.getProjectCards() == null ? List.of() : ruleModel.getProjectCards();
        List<ResumeDisplayModelDTO.ProjectCard> aiCards = model.getProjectCards() == null ? List.of() : model.getProjectCards();
        List<ResumeDisplayModelDTO.ProjectCard> result = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            ResumeDisplayModelDTO.ProjectCard rule = objectMapper.convertValue(rules.get(index), ResumeDisplayModelDTO.ProjectCard.class);
            ResumeDisplayModelDTO.ProjectCard polished = index < aiCards.size() ? aiCards.get(index) : null;
            if (polished != null) {
                String summary = cleanInvalidDisplay(polished.getSummary());
                if (isUsefulValue(summary)) {
                    rule.setSummary(summary);
                }
                List<String> responsibilities = unique(polished.getResponsibilities()).stream()
                        .filter(this::isUsefulValue)
                        .filter(item -> !isForbiddenDisplay(item))
                        .filter(item -> !isLowQualityDetail(item, rule.getTechStack(), rule.getSummary(), rule.getName()))
                        .limit(8)
                        .toList();
                if (!responsibilities.isEmpty()) {
                    rule.setResponsibilities(responsibilities);
                }
            }
            result.add(rule);
        }
        model.setProjectCards(result);
    }

    private void validateAchievements(ResumeDisplayModelDTO model, ResumeDisplayModelDTO ruleModel, Set<String> evidence) {
        List<ResumeDisplayModelDTO.AchievementCard> rules = ruleModel.getAchievementCards() == null ? List.of() : ruleModel.getAchievementCards();
        List<ResumeDisplayModelDTO.AchievementCard> result = new ArrayList<>();
        int index = 0;
        for (ResumeDisplayModelDTO.AchievementCard card : model.getAchievementCards() == null ? List.<ResumeDisplayModelDTO.AchievementCard>of() : model.getAchievementCards()) {
            ResumeDisplayModelDTO.AchievementCard rule = index < rules.size() ? rules.get(index) : null;
            if (!isUsefulValue(card.getTitle()) || !hasEvidence(card.getTitle(), evidence)) {
                card.setTitle(rule == null ? "" : rule.getTitle());
                card.setMeta(rule == null ? "" : rule.getMeta());
            }
            if (isUsefulValue(card.getTitle())) {
                if (card.getSourceRef() == null && rule != null) {
                    card.setSourceRef(rule.getSourceRef());
                }
                result.add(card);
            }
            index++;
        }
        model.setAchievementCards(result);
    }

    private void validateSummary(ResumeDisplayModelDTO model, ResumeDisplayModelDTO ruleModel) {
        ResumeDisplayModelDTO.SummaryCard rule = ruleModel == null ? null : ruleModel.getSummaryCard();
        ResumeDisplayModelDTO.SummaryCard summary = model.getSummaryCard();
        if (summary == null) {
            model.setSummaryCard(rule);
            return;
        }
        if (!isUsefulValue(summary.getContent()) || isSkillFragmentText(summary.getContent())) {
            model.setSummaryCard(rule);
            return;
        }
        if (summary.getSourceRef() == null && rule != null) {
            summary.setSourceRef(rule.getSourceRef());
        }
    }

    private void filterPending(ResumeDisplayModelDTO model) {
        model.setPendingItems(unique(model.getPendingItems()).stream()
                .map(this::shorten)
                .filter(this::isUsefulValue)
                .filter(item -> !isForbiddenDisplay(item))
                .filter(item -> !isLabelOnly(item))
                .limit(20)
                .toList());
    }

    private void normalizeModel(ResumeDisplayModelDTO model) {
        if (model == null) {
            return;
        }
        if (model.getSkillSummary() == null) {
            model.setSkillSummary(ResumeDisplayModelDTO.SkillSummary.builder().topSkills(List.of()).groups(List.of()).build());
        }
        model.setEducationCards(model.getEducationCards() == null ? List.of() : model.getEducationCards());
        model.setWorkExperienceCards(model.getWorkExperienceCards() == null ? List.of() : model.getWorkExperienceCards());
        model.setInternshipCards(model.getInternshipCards() == null ? List.of() : model.getInternshipCards());
        model.setCampusExperienceCards(model.getCampusExperienceCards() == null ? List.of() : model.getCampusExperienceCards());
        model.setProjectCards(model.getProjectCards() == null ? List.of() : model.getProjectCards());
        model.setAchievementCards(model.getAchievementCards() == null ? List.of() : model.getAchievementCards());
        model.setCertificateTags(model.getCertificateTags() == null ? List.of() : unique(model.getCertificateTags()));
        model.setPendingItems(model.getPendingItems() == null ? List.of() : model.getPendingItems());
    }

    private void applyDisplayMeta(
            ResumeDisplayModelDTO model,
            String generatedBy,
            boolean aiUsed,
            boolean fallback,
            String errorMessage,
            long durationMs,
            boolean cacheHit,
            String cacheKey,
            AiSelectionSnapshot selection) {
        if (model == null) {
            return;
        }
        model.setDisplayMeta(ResumeDisplayModelDTO.DisplayMeta.builder()
                .generatedBy(generatedBy)
                .aiDisplayUsed(aiUsed)
                .aiDisplayFallback(fallback)
                .aiDisplayErrorMessage(errorMessage == null ? "" : errorMessage)
                .aiDisplayDurationMs(durationMs)
                .cacheHit(cacheHit)
                .cacheKeyDigest(cacheKey == null || cacheKey.isBlank() ? "" : sha256(cacheKey).substring(0, 16))
                .displayPromptVersion(DISPLAY_PROMPT_VERSION)
                .displayAdapterVersion(DISPLAY_ADAPTER_VERSION)
                .modelName(modelName(selection))
                .build());
    }

    private String buildCacheKey(
            Long userId,
            Long resumeId,
            Map<String, Object> promptInput,
            AiSelectionSnapshot selection) {
        try {
            String selectionIdentity = selection == null ? "legacy-system" : selection.cacheIdentity(userId);
            return "resumeDisplay"
                    + ":userId=" + (userId == null ? "unknown" : userId)
                    + ":resumeId=" + (resumeId == null ? "unknown" : resumeId)
                    + ":structuredDataHash=" + sha256(objectMapper.writeValueAsString(promptInput))
                    + ":displayPromptVersion=" + DISPLAY_PROMPT_VERSION
                    + ":selection=" + selectionIdentity
                    + ":displayAdapterVersion=" + DISPLAY_ADAPTER_VERSION;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 展示优化缓存 key 构建失败", exception);
        }
    }

    private Set<String> collectEvidence(ResumeStructuredContentDTO content) {
        Set<String> evidence = new LinkedHashSet<>();
        if (content == null) {
            return evidence;
        }
        addEvidence(evidence, content.getName(), content.getPhone(), content.getEmail(), content.getJobIntention(), content.getHighestEducation(), content.getResumeType(), content.getSummary());
        addEvidence(evidence, content.getEducation());
        addEvidence(evidence, content.getSkills());
        addEvidence(evidence, content.getProjects());
        addEvidence(evidence, content.getWorkExperiences());
        addEvidence(evidence, content.getInternships());
        addEvidence(evidence, content.getCampusExperiences());
        addEvidence(evidence, content.getAwards());
        addEvidence(evidence, content.getCertificates());
        addEvidence(evidence, content.getOthers());
        if (content.getBasicInfo() != null) {
            addEvidence(evidence, new ArrayList<>(content.getBasicInfo().values()));
        }
        ResumeStructuredDataDTO data = content.getStructuredData();
        if (data != null) {
            addEvidence(evidence, data.getEducation());
            for (ResumeSourceRefDTO ref : data.getEducationSourceRefs() == null ? List.<ResumeSourceRefDTO>of() : data.getEducationSourceRefs()) {
                addEvidence(evidence, ref.getText());
            }
            addEvidence(evidence, data.getCertificates());
            addEvidence(evidence, data.getSummary());
            addEvidence(evidence, data.getSummarySourceRef() == null ? null : data.getSummarySourceRef().getText());
            addEvidence(evidence, data.getOthers());
            addEvidence(evidence, skills(data));
            for (ResumeExperienceDTO item : data.getExperiences() == null ? List.<ResumeExperienceDTO>of() : data.getExperiences()) {
                addEvidence(evidence, item.getOrganization(), item.getRole(), item.getDescription(), item.getSourceTitle());
                addEvidence(evidence, item.getBullets());
                addEvidence(evidence, item.getEvidence());
                addEvidence(evidence, item.getSourceRef() == null ? null : item.getSourceRef().getText());
            }
            for (ResumeProjectDTO item : data.getProjects() == null ? List.<ResumeProjectDTO>of() : data.getProjects()) {
                addEvidence(evidence, item.getName(), item.getDescription(), item.getRole(), item.getMentor(), item.getTimeRange());
                addEvidence(evidence, item.getTechStack());
                addEvidence(evidence, item.getResponsibilities());
                addEvidence(evidence, item.getEvidence());
                addEvidence(evidence, item.getSourceRef() == null ? null : item.getSourceRef().getText());
            }
            for (ResumeAchievementDTO item : data.getAchievements() == null ? List.<ResumeAchievementDTO>of() : data.getAchievements()) {
                addEvidence(evidence, item.getTitle(), item.getLevel(), item.getCompetition(), item.getRanking(), item.getTimeRange(), item.getDate());
                addEvidence(evidence, item.getEvidence());
                addEvidence(evidence, item.getSourceRef() == null ? null : item.getSourceRef().getText());
            }
        }
        for (ResumeRawSectionDTO section : content.getRawSections() == null ? List.<ResumeRawSectionDTO>of() : content.getRawSections()) {
            addEvidence(evidence, section.getOriginalTitle(), section.getDisplayName(), section.getNormalizedSection());
            if (section.getBlocks() != null) {
                for (ResumeRawSectionBlockDTO block : section.getBlocks()) {
                    addEvidence(evidence, block.getText());
                }
            }
        }
        return evidence;
    }

    private void addEvidence(Set<String> evidence, String... values) {
        for (String value : values) {
            if (isUsefulValue(value)) {
                evidence.add(value.strip());
            }
        }
    }

    private void addEvidence(Set<String> evidence, List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            addEvidence(evidence, value);
        }
    }

    private boolean hasEvidence(String value, Set<String> evidence) {
        if (!isUsefulValue(value)) {
            return false;
        }
        String target = compact(value);
        if (target.length() <= 2) {
            return true;
        }
        return evidence.stream().map(this::compact).anyMatch(source -> source.contains(target) || target.contains(source));
    }

    private List<String> collectExperienceLines(ResumeExperienceDTO item) {
        List<String> lines = new ArrayList<>();
        add(lines, item.getDescription());
        add(lines, item.getBullets());
        add(lines, item.getEvidence());
        return unique(lines).stream().map(this::shorten).filter(this::isUsefulValue).filter(line -> !isLabelOnly(line)).toList();
    }

    private List<String> skills(ResumeStructuredDataDTO data) {
        return skills(data == null ? null : data.getSkills());
    }

    private List<String> skills(ResumeSkillSetDTO skills) {
        List<String> result = new ArrayList<>();
        if (skills == null) {
            return result;
        }
        add(result, skills.getKeywords());
        if (skills.getGroups() != null) {
            skills.getGroups().values().forEach(values -> add(result, values));
        }
        return unique(result).stream().filter(this::isSkillTag).toList();
    }

    private String inferSkillGroupLabel(String skill) {
        if (List.of("Vue", "JavaScript", "TypeScript", "HTML", "CSS", "jQuery", "Bootstrap", "Element UI").contains(skill)) {
            return "前端";
        }
        if (List.of("MySQL", "PostgreSQL", "Redis", "MongoDB", "Oracle", "Elasticsearch", "SQL").contains(skill)) {
            return "数据库";
        }
        if (List.of("RabbitMQ", "RocketMQ", "Kafka", "Zookeeper", "Eureka", "Nginx", "Tomcat", "FastDFS").contains(skill)) {
            return "中间件";
        }
        if (List.of("Git", "Maven", "Gradle", "Docker", "Kubernetes", "Linux", "IDEA", "MATLAB", "Apache POI", "PowerDesigner").contains(skill)) {
            return "工具 / 环境";
        }
        if (List.of("OpenCV", "YOLO", "DETR", "Transformer", "PyTorch", "TensorFlow", "Scikit-learn", "Pandas").contains(skill)) {
            return "AI / 算法";
        }
        if (skill.matches("[A-Za-z0-9+#. -]{1,32}")) {
            return "后端";
        }
        return "其他技能";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String shorten(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = cleanDisplay(value);
        return cleaned.length() > MAX_TEXT_LENGTH ? cleaned.substring(0, MAX_TEXT_LENGTH) + "..." : cleaned;
    }

    private String cleanDisplay(String value) {
        return value == null ? "" : value
                .replaceFirst("^(项目名称|项目名|项目描述|项目简介|项目介绍|系统简介|技术栈|技术选型|技术架构|使用技术|开发框架|开发环境|开发工具|软件构架|软件架构|负责模块|责任描述|主要职责|主要工作|参与项目描述|职责|工作内容|公司名称|职位名称|工作时间|工作描述)[:：]\\s*", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String cleanInvalidDisplay(String value) {
        String cleaned = cleanDisplay(value);
        return isForbiddenDisplay(cleaned) ? "" : cleaned;
    }

    private String cleanProjectName(String value) {
        String cleaned = cleanInvalidDisplay(value);
        cleaned = cleaned.split("[，,；;。]")[0].strip();
        if (cleaned.matches("^项目经历\\s*\\d*$")) {
            return "";
        }
        if (cleaned.length() > 36 || cleaned.matches(".*[。！？!?；;].*")) {
            return "";
        }
        if (cleaned.matches("^(负责|参与|使用|采用|通过|实现|开发|编写|维护|优化|设计|管理|完成|做|对|是一个|该系统|该项目|主要|为了|左右).*")) {
            return "";
        }
        if (cleaned.matches(".*(?:是一个|该系统|该项目|采用|通过|使用|负责|参与|实现|开发|编写|维护|优化|设计|左右代码|其余代码|交给系统|自动生成).*")) {
            return "";
        }
        return cleaned;
    }

    private boolean isUsefulValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String cleaned = value.strip();
        if (cleaned.matches("^[\\d一二三四五六七八九十]+[.、．)]?$")) {
            return false;
        }
        return !cleaned.matches("^[\\s\\-_=+*#·•。.,，、;；:：|/\\\\\\[\\]()（）]+$");
    }

    private String normalizeDisplay(String value) {
        return cleanDisplay(value).replaceAll("\\s+", "").toLowerCase();
    }

    private boolean isSkillTag(String value) {
        if (!isUsefulValue(value)) {
            return false;
        }
        String cleaned = value.strip();
        if (cleaned.length() > 32 || cleaned.matches(".*[。！？!?].*")) {
            return false;
        }
        return !isLabelOnly(cleaned);
    }

    private boolean isLabelOnly(String value) {
        return value != null && value.strip().matches("^(毕业院校|学历|专业|学校|时间|项目名称|项目描述|技术栈|技术选型|开发环境|开发工具|软件构架|软件架构|负责模块|参与项目描述|公司名称|职位名称|工作时间|工作描述|邮箱|电话|姓名|未识别)[:：]?$");
    }

    private boolean isForbiddenDisplay(String value) {
        if (value == null) {
            return true;
        }
        String cleaned = value.strip();
        return cleaned.isBlank()
                || cleaned.matches("^(未识别|公司未识别|职位未识别|姓名未识别)$")
                || isLabelOnly(cleaned);
    }

    private boolean isLowQualityDetail(String value, List<String> tags, String... references) {
        String cleaned = cleanDisplay(value);
        if (!isUsefulValue(cleaned) || isLabelOnly(cleaned) || DETAIL_LABEL_PREFIX_PATTERN.matcher(cleaned).find()) {
            return true;
        }
        if (isSkillOnlyFragment(cleaned)) {
            return true;
        }
        for (String tag : tags == null ? List.<String>of() : tags) {
            if (duplicates(cleaned, tag)) {
                return true;
            }
        }
        return duplicates(cleaned, references);
    }

    private boolean isSkillOnlyFragment(String value) {
        String cleaned = cleanDisplay(value);
        if (cleaned.matches("(?i)^(Oracle|MySQL|Redis|MongoDB|JavaScript|Java|Ajax|jQuery|Vue|React|HTML|CSS|Spring|Spring Boot|Spring MVC|MyBatis|Tomcat|Nginx|Maven|IDEA|Linux|Docker|Git|SQL)$")) {
            return true;
        }
        return cleaned.matches("^[A-Za-z0-9+#.\\s,，、/\\\\-]+$")
                && cleaned.matches(".*[,，、/\\\\\\s].*")
                && !cleaned.matches(".*(?i)(responsible|develop|design|optimize|maintain|implement).*");
    }

    private boolean isSkillFragmentText(String value) {
        if (!isUsefulValue(value)) {
            return false;
        }
        String[] parts = value.split("[\\n,，、;；]+");
        int useful = 0;
        int skillLike = 0;
        for (String part : parts) {
            String cleaned = cleanDisplay(part);
            if (!isUsefulValue(cleaned)) {
                continue;
            }
            useful++;
            if (isSkillOnlyFragment(cleaned)) {
                skillLike++;
            }
        }
        if (useful >= 2) {
            return skillLike * 1.0 / useful >= 0.75;
        }
        return isSkillOnlyFragment(value)
                && !value.matches(".*(能力|经验|熟悉|精通|掌握|了解|负责|参与|开发|工作|团队|抗压|学习|沟通).*");
    }

    private boolean duplicates(String value, String... references) {
        if (!isUsefulValue(value)) {
            return true;
        }
        String target = compact(value);
        if (target.length() <= 1) {
            return true;
        }
        for (String reference : references) {
            if (!isUsefulValue(reference)) {
                continue;
            }
            String source = compact(reference);
            if (source.length() <= 1) {
                continue;
            }
            if (target.equals(source)) {
                return true;
            }
            String shorter = target.length() <= source.length() ? target : source;
            String longer = target.length() > source.length() ? target : source;
            if (shorter.length() >= 4 && longer.contains(shorter)) {
                return true;
            }
        }
        return false;
    }

    private ResumeSourceRefDTO mergeSourceRefs(List<ResumeSourceRefDTO> refs) {
        List<ResumeSourceRefDTO> usable = refs == null ? List.of() : refs.stream()
                .filter(ref -> ref != null && ref.getStartLine() != null && ref.getEndLine() != null && isUsefulValue(ref.getText()))
                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        int start = usable.stream().map(ResumeSourceRefDTO::getStartLine).min(Integer::compareTo).orElse(usable.get(0).getStartLine());
        int end = usable.stream().map(ResumeSourceRefDTO::getEndLine).max(Integer::compareTo).orElse(usable.get(0).getEndLine());
        String text = usable.stream()
                .map(ResumeSourceRefDTO::getText)
                .distinct()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return ResumeSourceRefDTO.builder()
                .startLine(start)
                .endLine(end)
                .text(text)
                .build();
    }

    private boolean validName(String value) {
        if (!isUsefulValue(value)) {
            return false;
        }
        String cleaned = value.strip();
        return cleaned.length() >= 2
                && cleaned.length() <= 8
                && !cleaned.matches(".*[0-9@.:：/\\\\|,，;；].*")
                && !cleaned.matches(".*(姓名|个人简历|简历|求职|岗位|电话|邮箱|学校|学院|大学|专业|项目|经历|技能|教育|证书|奖项|本人|未识别).*");
    }

    private String formatDateRange(String startDate, String endDate) {
        return firstNonBlank(startDate, "").isBlank() && firstNonBlank(endDate, "").isBlank()
                ? ""
                : List.of(startDate, endDate).stream().filter(this::isUsefulValue).reduce((left, right) -> left + " - " + right).orElse("");
    }

    private String matchDateRange(String value) {
        if (value == null) {
            return "";
        }
        var matcher = DATE_RANGE_PATTERN.matcher(value);
        return matcher.find() ? matcher.group().replaceAll("\\s+", "") : "";
    }

    private String match(String value, String regex) {
        if (value == null) {
            return "";
        }
        var matcher = Pattern.compile(regex).matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private String extractOrganization(String value) {
        return match(value, "[\\u4e00-\\u9fa5A-Za-z0-9（）()]{2,}(?:公司|集团|大学|学院|学校|实验室|中心|协会|社团)");
    }

    private String extractRole(String value) {
        return match(value, "(?:Java|Python|前端|后端|算法|软件)?(?:开发)?(?:工程师|实习生|负责人|组长|成员|经理|专员)");
    }

    private List<String> unique(List<String> values) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String cleaned = cleanDisplay(value);
            if (!cleaned.isBlank()) {
                seen.add(cleaned);
            }
        }
        return List.copyOf(seen);
    }

    private void add(List<String> target, String value) {
        if (isUsefulValue(value)) {
            target.add(value);
        }
    }

    private void add(List<String> target, List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            add(target, value);
        }
    }

    private boolean empty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private String compact(String value) {
        return cleanDisplay(value).replaceAll("[\\s,，、；;:：.。/\\\\|()（）\\[\\]【】]", "").toLowerCase();
    }

    private ResumeDisplayModelDTO copy(ResumeDisplayModelDTO model) {
        return objectMapper.convertValue(model, ResumeDisplayModelDTO.class);
    }

    private String modelName() {
        // Rule-derived display metadata has no provider invocation context;
        // never query another user's Credential merely to populate a label.
        return "unknown";
    }

    private String modelName(AiSelectionSnapshot selection) {
        if (selection != null && selection.model() != null && !selection.model().isBlank()) {
            return selection.model();
        }
        return modelName();
    }

    private AiGatewayRequest displayRequest(String renderedPrompt) {
        String boundary = "输入：";
        int boundaryIndex = renderedPrompt.lastIndexOf(boundary);
        if (boundaryIndex < 0) {
            return new AiGatewayRequest("RESUME_DISPLAY_MODEL", renderedPrompt, "{}");
        }
        String system = renderedPrompt.substring(0, boundaryIndex).strip();
        String user = renderedPrompt.substring(boundaryIndex).strip();
        return new AiGatewayRequest("RESUME_DISPLAY_MODEL", system, user);
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String safeError(String ignoredMessage) {
        // Never surface parser/provider text because it may contain resume-derived content.
        return "AI 展示优化失败";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String extractJsonObject(String value) throws JsonProcessingException {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("(?i)^```(?:json)?\\s*", "");
            stripped = stripped.replaceFirst("\\s*```$", "");
        }
        stripped = stripped.strip();
        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            return stripped;
        }
        int start = stripped.indexOf('{');
        if (start < 0) {
            throw new JsonProcessingException("AI 输出中未找到 JSON 对象") {
            };
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < stripped.length(); index++) {
            char current = stripped.charAt(index);
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
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(start, index + 1);
                }
            }
        }
        throw new JsonProcessingException("AI 输出中的 JSON 对象不完整") {
        };
    }
}
