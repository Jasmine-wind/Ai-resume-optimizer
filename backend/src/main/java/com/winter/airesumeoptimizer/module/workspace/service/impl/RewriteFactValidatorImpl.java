package com.winter.airesumeoptimizer.module.workspace.service.impl;

import com.winter.airesumeoptimizer.module.workspace.dto.RewriteFactValidationResult;
import com.winter.airesumeoptimizer.module.workspace.enums.RewriteFactViolationCode;
import com.winter.airesumeoptimizer.module.workspace.service.RewriteFactValidator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 单 Bullet 的确定性内容审查器与技术安全检查器。
 *
 * <p>它仍然识别未知 token、中文事实片段、极性/程度/责任变化以及数字语义变化，
 * 供服务层生成 review advisory；空值、超长、身份泄露、控制字符和不支持的脚本仍然技术性 fail closed。
 */
@Service
public class RewriteFactValidatorImpl implements RewriteFactValidator {

    static final int MAX_SUGGESTED_LENGTH = 4000;

    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+#.\\-]*");
    private static final Pattern NUMBER_CLAIM_PATTERN = Pattern.compile(
            "\\d+(?:[.,]\\d+)*(?:%|倍|成|个|项|人|家|年|月|天|周|小时|分钟|秒|万|亿|千|百|qps|ms)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_QUANT_PATTERN = Pattern.compile(
            "百分之[一二两三四五六七八九十百千万亿零几数]+"
                    + "|[一二两三四五六七八九十百千万亿零几数]+(?:倍|成|万|亿|千|百|个|项|人|家|年|月|天|周)"
                    + "|上万|数十|数百|数千|数万|数亿");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("\\d{4}\\s*年|\\d{4}\\s*[./\\-]\\s*\\d{1,2}(?:\\s*[./\\-]\\s*\\d{1,2})?");
    private static final Pattern ELEMENT_ID_PATTERN = Pattern.compile(
            "(?:^|[^A-Za-z0-9])((?:s|e|b|c|ent|sec)-\\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:[^A-Za-z0-9]|$)");
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile(
            "[\\p{P}\\p{S}]+|但是|然而|并且|以及|但|而|并(?!发)|(?<!涉)及|且|和|(?<!参)与|或|"
                    + "\\b(?:but|and|or|while|whereas)\\b");
    private static final Pattern HAN_RUN_PATTERN = Pattern.compile("[\\p{IsHan}]+");

    /** 只用于允许英文连接词；所有其它新增 Latin token 都按未知事实拒绝。 */
    private static final Set<String> LATIN_STYLE_WORDS = Set.of(
            "and", "or", "with", "for", "the", "a", "an", "of", "to", "by", "via", "using");

    private static final Set<String> LATIN_TECH_TERMS = Set.of(
            "redis", "mysql", "mariadb", "postgresql", "postgres", "oracle", "sqlserver", "mongodb",
            "elasticsearch", "solr", "kafka", "rabbitmq", "rocketmq", "activemq", "pulsar", "zookeeper",
            "nacos", "eureka", "consul", "dubbo", "spring", "springboot", "springcloud", "springmvc",
            "mybatis", "hibernate", "jpa", "netty", "tomcat", "jetty", "undertow", "jvm", "java",
            "kotlin", "scala", "golang", "rust", "python", "django", "flask", "fastapi", "pandas", "php",
            "javascript", "typescript", "nodejs", "node.js", "vue", "react", "angular", "webpack", "vite",
            "nginx", "apache", "docker", "kubernetes", "k8s", "helm", "istio", "jenkins", "gitlab",
            "github", "maven", "gradle", "linux", "unix", "hadoop", "spark", "flink", "storm", "hive",
            "hbase", "clickhouse", "tidb", "doris", "presto", "minio", "aws", "azure", "aliyun",
            "grpc", "thrift", "webservice", "websocket", "graphql", "oauth", "oauth2", "jwt", "sso",
            "prometheus", "grafana", "skywalking", "zipkin", "sentry", "tensorflow", "pytorch", "llm",
            "aigc", "rag", "devops", "saas", "paas", "sdk", "openapi", "restful", "microservice",
            "microservices", "memcached", "etcd", "terraform", "ansible", "quarkus", "airflow", "junit",
            "mockito", "selenium", "langchain", "ollama", "milvus", "faiss", "pinia", "nuxt", "nextjs");

    private static final List<String> CHINESE_TECH_TERMS = List.of(
            "微服务", "服务网格", "消息队列", "分布式", "高并发", "高可用", "高性能", "分库分表", "读写分离",
            "机器学习", "深度学习", "大模型", "大语言模型", "人工智能", "智能体", "推荐算法", "推荐系统",
            "搜索引擎", "检索增强", "知识库", "知识图谱", "向量", "数据仓库", "数据湖", "实时计算",
            "流式计算", "容器化", "云原生", "中台", "灰度发布", "熔断", "限流", "负载均衡",
            "性能调优", "全链路压测", "链路追踪", "异地多活", "容灾", "数据治理", "网络编程");

    private static final List<String> MULTIPLIER_MARKERS = List.of(
            "翻倍", "翻番", "数倍", "量级提升", "指数级");
    private static final List<String> ACHIEVEMENT_MARKERS = List.of(
            "获奖", "荣获", "奖项", "创新奖", "专利", "论文", "表彰", "冠军", "第一名", "优秀员工", "晋升",
            "显著提升", "明显提升", "大幅提高", "大幅提升", "大幅降低", "明显提高", "明显改善",
            "提升", "提高", "降低", "减少", "增长", "满意度", "客户认可", "按期交付", "零故障",
            "零事故", "零缺陷", "业内领先", "行业首创", "首创");
    private static final List<String> CAUSAL_MARKERS = List.of(
            "从而", "进而", "因此", "由此", "带动", "促使", "使得", "得益于", "归因于", "使");
    private static final List<String> SCOPE_TIME_MARKERS = List.of(
            "全公司", "公司级", "集团级", "集团", "部门级", "跨部门", "跨团队", "跨区域", "全国",
            "全球", "海外市场", "行业领先", "国内领先", "国际领先", "长期", "短期", "按期", "提前",
            "个月内", "年内", "周内", "天内");

    private static final List<String> NEGATION_MARKERS = List.of(
            "尚未", "从未", "并未", "没有", "未使用", "未参与", "未负责", "未完成", "未具备", "不具备",
            "缺少", "缺乏", "欠缺", "避免", "禁止", "排除", "不能", "无法", "不曾", "did not", "does not",
            "do not", "never", "without", "not", "lack", "lacks", "lacked", "lacking", "avoid", "avoids",
            "avoided", "excluding", "未", "不", "无");

    private static final List<String> RESPONSIBILITY_TIER3 = List.of(
            "技术负责人", "团队负责人", "项目负责人", "独立负责", "从零到一", "从0到1", "主导", "牵头",
            "主持", "带队", "带领", "负责人", "架构师", "统筹", "owner", "leader", "team lead", "tech lead");
    private static final List<String> RESPONSIBILITY_TIER2 = List.of(
            "独立完成", "独立开发", "独立设计", "独立搭建", "独立实现", "设计并实现", "总体设计", "负责", "承担", "独立");
    private static final List<String> RESPONSIBILITY_TIER1 = List.of("参与", "协助", "配合");
    private static final Pattern MANAGE_PEOPLE_PATTERN = Pattern.compile("管理\\s*\\d+\\s*人|带\\s*\\d+\\s*人");

    private static final List<String> PROFICIENCY_TIER4 = List.of(
            "精通", "专家", "丰富实践经验", "丰富经验", "深厚经验", "expert", "expertise");
    private static final List<String> PROFICIENCY_TIER3 = List.of(
            "熟练掌握", "熟练使用", "深入掌握", "熟练", "proficient");
    private static final List<String> PROFICIENCY_TIER2 = List.of(
            "掌握", "熟悉", "具备经验", "实践经验", "experienced", "familiar");
    private static final List<String> PROFICIENCY_TIER1 = List.of(
            "使用过", "接触过", "了解", "使用", "采用", "used", "use", "worked with");

    /** 动作谓词不是风格词；允许调整位置，但不允许替换成原文没有的工作类型或完成结果。 */
    private static final List<String> FACT_PREDICATES = List.of(
            "完成", "实现", "开发", "维护", "优化", "设计", "编写", "搭建", "建设", "交付", "测试",
            "运营", "管理", "部署", "重构", "迁移", "分析", "处理", "解决", "定位");

    /**
     * 可新增或替换的纯表达词。事实名词、结果、范围、程度和因果词不得进入此集合。
     * 顺序按长词优先，避免短词破坏长词识别。
     */
    private static final List<String> STYLE_WORDS = List.of(
            "技术负责人", "团队负责人", "项目负责人", "丰富实践经验", "熟练掌握", "熟练使用", "深入掌握",
            "独立完成", "独立开发", "独立设计", "独立搭建", "独立实现", "设计并实现", "使用过", "接触过",
            "承担", "负责", "参与", "协助", "配合", "完成", "实现", "使用", "采用", "开发", "维护", "优化",
            "设计", "编写", "进行", "基于", "针对", "围绕", "相关", "工作", "项目", "以及", "并且",
            "从事", "负责了", "的", "了", "和", "与", "及", "并", "对", "使", "为", "在", "中");

    @Override
    public RewriteFactValidationResult validate(String originalText, String suggestedText) {
        if (suggestedText == null || suggestedText.isBlank()) {
            return fail(RewriteFactViolationCode.EMPTY_OR_BLANK, "AI 没有给出可用的改写文本");
        }
        if (suggestedText.length() > MAX_SUGGESTED_LENGTH) {
            return fail(RewriteFactViolationCode.OVERSIZED, "AI 改写文本超过要点长度上限");
        }
        if (containsFormatControl(suggestedText)) {
            return fail(RewriteFactViolationCode.CONTROL_CHARACTER, "AI 改写包含不可见格式控制字符");
        }

        String original = normalize(originalText == null ? "" : originalText);
        String suggestionWithCase = Normalizer.normalize(suggestedText, Normalizer.Form.NFKC);
        if (containsUnsupportedScript(suggestionWithCase)) {
            return fail(RewriteFactViolationCode.UNSUPPORTED_SCRIPT, "AI 改写包含无法安全处理的字符脚本");
        }
        String suggestion = suggestionWithCase.toLowerCase(Locale.ROOT);
        String originalCorpus = stripWhitespace(original);
        String suggestionCorpus = stripWhitespace(suggestion);

        RewriteFactValidationResult result = checkElementIdentityLeak(suggestion);
        if (result != null) {
            return result;
        }
        result = checkExactClaims(original, suggestion);
        if (result != null) {
            return result;
        }
        result = checkLatinClosure(original, suggestionWithCase);
        if (result != null) {
            return result;
        }
        result = checkNewMarkers(
                CHINESE_TECH_TERMS, suggestionCorpus, originalCorpus,
                RewriteFactViolationCode.NEW_TECHNOLOGY, "改写引入了原文没有的技术或能力描述");
        if (result != null) {
            return result;
        }
        result = checkResponsibility(originalCorpus, suggestionCorpus);
        if (result != null) {
            return result;
        }
        result = checkProficiency(originalCorpus, suggestionCorpus);
        if (result != null) {
            return result;
        }
        result = checkNewMarkers(
                ACHIEVEMENT_MARKERS, suggestionCorpus, originalCorpus,
                RewriteFactViolationCode.NEW_ACHIEVEMENT, "改写引入了原文没有的成果或效果结论");
        if (result != null) {
            return result;
        }
        result = checkNewMarkers(
                CAUSAL_MARKERS, suggestionCorpus, originalCorpus,
                RewriteFactViolationCode.NEW_ACHIEVEMENT, "改写引入了原文没有的因果或效果结论");
        if (result != null) {
            return result;
        }
        result = checkNewMarkers(
                SCOPE_TIME_MARKERS, suggestionCorpus, originalCorpus,
                RewriteFactViolationCode.NEW_SCOPE_OR_TIME, "改写引入了原文没有的范围或时间事实");
        if (result != null) {
            return result;
        }
        result = checkAmbiguousRelations(suggestion);
        if (result != null) {
            return result;
        }
        result = checkPolarity(original, suggestion);
        if (result != null) {
            return result;
        }
        result = checkHanClosure(original, suggestion);
        if (result != null) {
            return result;
        }
        return RewriteFactValidationResult.pass();
    }

    private RewriteFactValidationResult checkExactClaims(String original, String suggestion) {
        RewriteFactValidationResult result = checkClaimMultiset(
                original, suggestion, NUMBER_CLAIM_PATTERN,
                RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM,
                "改写引入或改变了数字、单位或量化结果", true);
        if (result != null) {
            return result;
        }
        result = checkClaimMultiset(
                original, suggestion, CHINESE_QUANT_PATTERN,
                RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM,
                "改写引入或改变了中文量化结果", false);
        if (result != null) {
            return result;
        }
        result = checkClaimMultiset(
                original, suggestion, DATE_PATTERN,
                RewriteFactViolationCode.NEW_SCOPE_OR_TIME,
                "改写引入或改变了日期事实", false);
        if (result != null) {
            return result;
        }
        result = checkNumberClaimRelations(original, suggestion);
        if (result != null) {
            return result;
        }
        return checkNewMarkers(
                MULTIPLIER_MARKERS, stripWhitespace(suggestion), stripWhitespace(original),
                RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM, "改写引入了原文没有的倍数或量级声明");
    }

    private RewriteFactValidationResult checkClaimMultiset(
            String original,
            String suggestion,
            Pattern pattern,
            RewriteFactViolationCode code,
            String message,
            boolean skipLatinEmbeddedNumbers) {
        Map<String, Long> originalClaims = extractClaims(original, pattern, skipLatinEmbeddedNumbers);
        Map<String, Long> suggestionClaims = extractClaims(suggestion, pattern, skipLatinEmbeddedNumbers);
        for (Map.Entry<String, Long> entry : suggestionClaims.entrySet()) {
            if (entry.getValue() > originalClaims.getOrDefault(entry.getKey(), 0L)) {
                return fail(code, message);
            }
        }
        return null;
    }

    private Map<String, Long> extractClaims(String text, Pattern pattern, boolean skipLatinEmbeddedNumbers) {
        Map<String, Long> claims = new HashMap<>();
        String compact = stripWhitespace(text);
        Matcher matcher = pattern.matcher(compact);
        while (matcher.find()) {
            if (skipLatinEmbeddedNumbers && isPartOfLatinToken(compact, matcher.start(), matcher.end())) {
                continue;
            }
            claims.merge(matcher.group().toLowerCase(Locale.ROOT), 1L, Long::sum);
        }
        return claims;
    }

    private RewriteFactValidationResult checkNumberClaimRelations(String original, String suggestion) {
        List<ClaimContext> originalContexts = claimContexts(original);
        for (ClaimContext candidate : claimContexts(suggestion)) {
            boolean matched = originalContexts.stream()
                    .anyMatch(source -> source.claim().equals(candidate.claim())
                            && anchorsCompatible(candidate.anchors(), source.anchors()));
            if (!matched) {
                return fail(
                        RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM,
                        "改写改变了数字与原对象或指标的关系");
            }
        }
        return null;
    }

    private List<ClaimContext> claimContexts(String text) {
        List<ClaimContext> contexts = new ArrayList<>();
        for (String clause : CLAUSE_SEPARATOR.split(text)) {
            String compact = stripWhitespace(clause);
            Matcher matcher = NUMBER_CLAIM_PATTERN.matcher(compact);
            Set<String> anchors = extractFactAnchors(clause);
            while (matcher.find()) {
                if (!isPartOfLatinToken(compact, matcher.start(), matcher.end())) {
                    contexts.add(new ClaimContext(
                            matcher.group().toLowerCase(Locale.ROOT), Set.copyOf(anchors)));
                }
            }
        }
        return contexts;
    }

    /** 多个高风险事实共享一个无法继续切分的关系片段时，无法安全绑定则整体拒绝。 */
    private RewriteFactValidationResult checkAmbiguousRelations(String suggestion) {
        for (String clause : CLAUSE_SEPARATOR.split(suggestion)) {
            if (clause.isBlank()) {
                continue;
            }
            if (countNumberClaims(clause) > 1
                    || countPresentTiers(clause, RESPONSIBILITY_TIER1, RESPONSIBILITY_TIER2,
                            RESPONSIBILITY_TIER3) > 1
                    || countPresentTiers(clause, PROFICIENCY_TIER1, PROFICIENCY_TIER2,
                            PROFICIENCY_TIER3, PROFICIENCY_TIER4) > 1
                    || countPresentMarkers(clause, FACT_PREDICATES) > 1) {
                return fail(RewriteFactViolationCode.UNDETERMINED, "改写包含无法安全绑定到单一事实的多个关系");
            }
            if (containsAny(clause, NEGATION_MARKERS) && contentAnchorCount(clause) > 1) {
                return fail(RewriteFactViolationCode.UNDETERMINED, "改写中的否定关系无法安全绑定到单一事实");
            }
        }
        return null;
    }

    @SafeVarargs
    private final int countPresentTiers(String text, List<String>... tiers) {
        int count = 0;
        for (List<String> tier : tiers) {
            if (containsAny(text, tier)) {
                count++;
            }
        }
        return count;
    }

    private int countPresentMarkers(String text, List<String> markers) {
        int count = 0;
        for (String marker : markers) {
            if (text.contains(normalize(marker))) {
                count++;
            }
        }
        return count;
    }

    private int countNumberClaims(String text) {
        int count = 0;
        String compact = stripWhitespace(text);
        Matcher matcher = NUMBER_CLAIM_PATTERN.matcher(compact);
        while (matcher.find()) {
            if (!isPartOfLatinToken(compact, matcher.start(), matcher.end())) {
                count++;
            }
        }
        return count;
    }

    private int contentAnchorCount(String text) {
        int count = 0;
        for (String anchor : extractFactAnchors(text)) {
            if (anchor.startsWith("han:") || anchor.startsWith("latin:")) {
                count++;
            }
        }
        return count;
    }

    private RewriteFactValidationResult checkLatinClosure(String original, String suggestion) {
        Set<String> originalTokens = extractLatinTokens(original);
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(suggestion);
        while (matcher.find()) {
            String rawToken = matcher.group();
            String token = rawToken.toLowerCase(Locale.ROOT);
            if (originalTokens.contains(token) || LATIN_STYLE_WORDS.contains(token)) {
                continue;
            }
            if (LATIN_TECH_TERMS.contains(token) || token.chars().anyMatch(Character::isDigit)) {
                return fail(RewriteFactViolationCode.NEW_TECHNOLOGY, "改写引入了原文没有的技术名称");
            }
            if (!rawToken.equals(rawToken.toLowerCase(Locale.ROOT))) {
                return fail(RewriteFactViolationCode.NEW_ENTITY, "改写引入了原文没有的技术或实体名称");
            }
            return fail(RewriteFactViolationCode.UNDETERMINED, "改写引入了无法由原文证明的英文事实词");
        }
        return null;
    }

    private Set<String> extractLatinTokens(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private RewriteFactValidationResult checkResponsibility(String original, String suggestion) {
        int originalTier = responsibilityTier(original);
        int suggestionTier = responsibilityTier(suggestion);
        if (suggestionTier > originalTier
                || attributeEscalates(original, suggestion, this::responsibilityTier)) {
            return fail(RewriteFactViolationCode.RESPONSIBILITY_ESCALATION, "改写升级了原文的责任级别");
        }
        return null;
    }

    private int responsibilityTier(String text) {
        if (containsAny(text, RESPONSIBILITY_TIER3) || MANAGE_PEOPLE_PATTERN.matcher(text).find()) {
            return 3;
        }
        if (containsAny(text, RESPONSIBILITY_TIER2)) {
            return 2;
        }
        if (containsAny(text, RESPONSIBILITY_TIER1)) {
            return 1;
        }
        return 0;
    }

    private RewriteFactValidationResult checkProficiency(String original, String suggestion) {
        if (proficiencyTier(suggestion) > proficiencyTier(original)
                || attributeEscalates(original, suggestion, this::proficiencyTier)) {
            return fail(RewriteFactViolationCode.UNDETERMINED, "改写升级了原文的能力或熟练程度");
        }
        return null;
    }

    private boolean attributeEscalates(
            String original, String suggestion, ToIntFunction<String> tierFunction) {
        List<AttributedClause> originalClauses = attributedClauses(original, tierFunction);
        for (AttributedClause candidate : attributedClauses(suggestion, tierFunction)) {
            if (candidate.level() == 0 || candidate.anchors().isEmpty()) {
                continue;
            }
            for (String candidateAnchor : candidate.anchors()) {
                int supportedLevel = originalClauses.stream()
                        .filter(source -> source.anchors().stream()
                                .anyMatch(sourceAnchor -> anchorCompatible(candidateAnchor, sourceAnchor)))
                        .mapToInt(AttributedClause::level)
                        .max()
                        .orElse(0);
                if (candidate.level() > supportedLevel) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<AttributedClause> attributedClauses(
            String text, ToIntFunction<String> tierFunction) {
        List<AttributedClause> clauses = new ArrayList<>();
        for (String clause : CLAUSE_SEPARATOR.split(text)) {
            if (!clause.isBlank()) {
                clauses.add(new AttributedClause(tierFunction.applyAsInt(clause), extractFactAnchors(clause)));
            }
        }
        return clauses;
    }

    private int proficiencyTier(String text) {
        if (containsAny(text, PROFICIENCY_TIER4)) {
            return 4;
        }
        if (containsAny(text, PROFICIENCY_TIER3)) {
            return 3;
        }
        if (containsAny(text, PROFICIENCY_TIER2)) {
            return 2;
        }
        if (containsAny(text, PROFICIENCY_TIER1)) {
            return 1;
        }
        return 0;
    }

    /** 同一事实锚点的肯定/否定极性必须与原文唯一对应；删除整个事实仍允许。 */
    private RewriteFactValidationResult checkPolarity(String original, String suggestion) {
        Map<String, Set<Boolean>> originalPolarity = polarityByAnchor(original);
        Map<String, Set<Boolean>> suggestionPolarity = polarityByAnchor(suggestion);
        for (Map.Entry<String, Set<Boolean>> entry : suggestionPolarity.entrySet()) {
            Set<Boolean> originalValues = originalPolarity.get(entry.getKey());
            if (originalValues == null || originalValues.size() != 1 || !originalValues.equals(entry.getValue())) {
                return fail(RewriteFactViolationCode.UNDETERMINED, "改写改变了事实的肯定或否定关系");
            }
        }
        return null;
    }

    private Map<String, Set<Boolean>> polarityByAnchor(String text) {
        Map<String, Set<Boolean>> result = new HashMap<>();
        for (String clause : CLAUSE_SEPARATOR.split(text)) {
            if (clause.isBlank()) {
                continue;
            }
            boolean negated = containsAny(clause, NEGATION_MARKERS);
            for (String anchor : extractFactAnchors(clause)) {
                result.computeIfAbsent(anchor, ignored -> new HashSet<>()).add(negated);
            }
        }
        return result;
    }

    private RewriteFactValidationResult checkHanClosure(String original, String suggestion) {
        List<Set<String>> sourceClauses = new ArrayList<>();
        for (String clause : CLAUSE_SEPARATOR.split(original)) {
            if (!clause.isBlank()) {
                sourceClauses.add(extractFactAnchors(clause));
            }
        }

        boolean retainedFact = false;
        for (String clause : CLAUSE_SEPARATOR.split(suggestion)) {
            if (clause.isBlank()) {
                continue;
            }
            Set<String> candidateAnchors = extractFactAnchors(clause);
            if (candidateAnchors.isEmpty()) {
                continue;
            }
            retainedFact = true;
            boolean supported = sourceClauses.stream()
                    .anyMatch(sourceAnchors -> closureAnchorsSupported(candidateAnchors, sourceAnchors));
            if (!supported) {
                return fail(RewriteFactViolationCode.UNDETERMINED, "改写重组或引入了无法由原文证明的事实描述");
            }
        }
        if (!retainedFact) {
            return fail(RewriteFactViolationCode.UNDETERMINED, "改写没有保留可由原文证明的事实内容");
        }
        return null;
    }

    private boolean closureAnchorsSupported(Set<String> candidateAnchors, Set<String> sourceAnchors) {
        for (String candidate : candidateAnchors) {
            if (candidate.startsWith("han:") && candidate.length() == "han:".length() + 1) {
                return false;
            }
            boolean supported = sourceAnchors.stream()
                    .anyMatch(source -> closureAnchorSupported(candidate, source));
            if (!supported) {
                return false;
            }
        }
        return true;
    }

    private boolean closureAnchorSupported(String candidate, String source) {
        if (!candidate.startsWith("han:") || !source.startsWith("han:")) {
            return candidate.equals(source);
        }
        String candidateValue = candidate.substring("han:".length());
        String sourceValue = source.substring("han:".length());
        return sourceValue.contains(candidateValue);
    }

    private Set<String> extractFactAnchors(String text) {
        Set<String> anchors = new HashSet<>();
        Matcher latinMatcher = LATIN_TOKEN_PATTERN.matcher(text);
        while (latinMatcher.find()) {
            String token = latinMatcher.group().toLowerCase(Locale.ROOT);
            if (!LATIN_STYLE_WORDS.contains(token)) {
                anchors.add("latin:" + token);
            }
        }
        addProtectedAnchors(anchors, text, FACT_PREDICATES, "predicate:");
        addProtectedAnchors(anchors, text, ACHIEVEMENT_MARKERS, "marker:");
        addProtectedAnchors(anchors, text, CAUSAL_MARKERS, "marker:");
        addProtectedAnchors(anchors, text, SCOPE_TIME_MARKERS, "marker:");

        String hanOnly = text;
        hanOnly = LATIN_TOKEN_PATTERN.matcher(hanOnly).replaceAll(" ");
        hanOnly = NUMBER_CLAIM_PATTERN.matcher(hanOnly).replaceAll(" ");
        for (String word : allRemovableWords()) {
            hanOnly = hanOnly.replace(word, " ");
        }
        Matcher hanMatcher = HAN_RUN_PATTERN.matcher(hanOnly);
        while (hanMatcher.find()) {
            String value = hanMatcher.group();
            if (!value.isBlank()) {
                anchors.add("han:" + value);
            }
        }
        return anchors;
    }

    private void addProtectedAnchors(
            Set<String> anchors, String text, List<String> markers, String prefix) {
        for (String marker : markers) {
            String normalizedMarker = normalize(marker);
            if (text.contains(normalizedMarker)) {
                anchors.add(prefix + normalizedMarker);
            }
        }
    }

    private List<String> allRemovableWords() {
        List<String> words = new ArrayList<>(STYLE_WORDS);
        words.addAll(NEGATION_MARKERS);
        words.addAll(RESPONSIBILITY_TIER3);
        words.addAll(RESPONSIBILITY_TIER2);
        words.addAll(RESPONSIBILITY_TIER1);
        words.addAll(PROFICIENCY_TIER4);
        words.addAll(PROFICIENCY_TIER3);
        words.addAll(PROFICIENCY_TIER2);
        words.addAll(PROFICIENCY_TIER1);
        words.addAll(FACT_PREDICATES);
        words.addAll(ACHIEVEMENT_MARKERS);
        words.addAll(CAUSAL_MARKERS);
        words.addAll(SCOPE_TIME_MARKERS);
        words.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return words;
    }

    private RewriteFactValidationResult checkElementIdentityLeak(String suggestion) {
        if (ELEMENT_ID_PATTERN.matcher(suggestion).find()) {
            return fail(RewriteFactViolationCode.ELEMENT_IDENTITY_LEAK, "AI 输出中包含疑似结构化元素标识");
        }
        return null;
    }

    private RewriteFactValidationResult checkNewMarkers(
            List<String> markers,
            String suggestion,
            String original,
            RewriteFactViolationCode code,
            String message) {
        for (String marker : markers) {
            String normalizedMarker = stripWhitespace(normalize(marker));
            if (suggestion.contains(normalizedMarker) && !original.contains(normalizedMarker)) {
                return fail(code, message);
            }
        }
        return null;
    }

    private boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(normalize(marker))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFormatControl(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.FORMAT);
    }

    private boolean containsUnsupportedScript(String text) {
        return text.codePoints().anyMatch(codePoint -> {
            int type = Character.getType(codePoint);
            if (Character.isLetter(codePoint)) {
                return !isAsciiLatinLetter(codePoint)
                        && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN;
            }
            if (Character.isDigit(codePoint)) {
                return codePoint < '0' || codePoint > '9';
            }
            return type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
        });
    }

    private boolean isAsciiLatinLetter(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z');
    }

    private boolean anchorsCompatible(Set<String> candidateAnchors, Set<String> sourceAnchors) {
        if (candidateAnchors.isEmpty()) {
            return sourceAnchors.isEmpty();
        }
        for (String candidate : candidateAnchors) {
            boolean supported = sourceAnchors.stream()
                    .anyMatch(source -> anchorCompatible(candidate, source));
            if (!supported) {
                return false;
            }
        }
        return true;
    }

    private boolean anchorCompatible(String candidate, String source) {
        if (!candidate.startsWith("han:") || !source.startsWith("han:")) {
            return candidate.equals(source);
        }
        String candidateValue = candidate.substring("han:".length());
        String sourceValue = source.substring("han:".length());
        return candidateValue.contains(sourceValue) || sourceValue.contains(candidateValue);
    }

    private boolean isPartOfLatinToken(String text, int start, int end) {
        boolean latinBefore = start > 0 && isLatinLetter(text.charAt(start - 1));
        boolean latinAfter = end < text.length() && isLatinLetter(text.charAt(end));
        return latinBefore || latinAfter;
    }

    private boolean isLatinLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private RewriteFactValidationResult fail(RewriteFactViolationCode code, String message) {
        return RewriteFactValidationResult.fail(code, message);
    }

    private String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private String stripWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }

    private record ClaimContext(String claim, Set<String> anchors) {
    }

    private record AttributedClause(int level, Set<String> anchors) {
    }
}
