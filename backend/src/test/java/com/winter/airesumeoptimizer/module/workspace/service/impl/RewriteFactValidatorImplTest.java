package com.winter.airesumeoptimizer.module.workspace.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.winter.airesumeoptimizer.module.workspace.dto.RewriteFactValidationResult;
import com.winter.airesumeoptimizer.module.workspace.enums.RewriteFactViolationCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 内容审查：识别事实变化供 advisory 使用；技术安全输出仍 fail closed。
 */
class RewriteFactValidatorImplTest {

    private final RewriteFactValidatorImpl validator = new RewriteFactValidatorImpl();

    private RewriteFactValidationResult validate(String original, String suggested) {
        return validator.validate(original, suggested);
    }

    @Test
    void shouldAllowSynonymRewordingWithoutFactExpansion() {
        RewriteFactValidationResult result = validate(
                "负责订单服务后端接口开发",
                "承担订单服务后端接口的开发工作");

        assertThat(result.passed()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("highRiskFactExpansionCases")
    void shouldFailClosedForHighRiskFactExpansion(
            String description, String original, String suggested) {
        RewriteFactValidationResult result = validate(original, suggested);

        assertThat(result.passed())
                .as(description)
                .isFalse();
        assertThat(result.code()).isNotEqualTo(RewriteFactViolationCode.OK);
    }

    private static Stream<Arguments> highRiskFactExpansionCases() {
        return Stream.of(
                Arguments.of(
                        "whole-token matching must not treat JavaScript as Java",
                        "使用 JavaScript 开发前端页面",
                        "使用 Java 开发前端页面"),
                Arguments.of(
                        "a negated technology must not become a positive claim",
                        "使用 Java 开发订单服务，未使用 Kafka",
                        "使用 Java 和 Kafka 开发订单服务"),
                Arguments.of(
                        "existing polarity markers must stay attached to the same fact",
                        "未使用 Java，使用 Kafka",
                        "使用 Java，未使用 Kafka"),
                Arguments.of(
                        "English negation must not be removed from an existing fact",
                        "Used Java but did not use Kafka",
                        "Used Java but use Kafka"),
                Arguments.of(
                        "conjunctions must not allow polarity to move between technologies",
                        "未使用 Kafka 但使用 Redis",
                        "使用 Kafka 但未使用 Redis"),
                Arguments.of(
                        "and-style conjunctions must not allow polarity to move",
                        "未使用 Kafka 与使用 Redis",
                        "使用 Kafka 与未使用 Redis"),
                Arguments.of(
                        "implicit multi-fact text must not share polarity",
                        "未使用 Kafka 使用 Redis",
                        "使用 Kafka 未使用 Redis"),
                Arguments.of(
                        "missing-capability wording must not become a positive capability",
                        "缺少 Kafka 经验",
                        "Kafka 经验"),
                Arguments.of(
                        "avoidance wording must not become positive usage",
                        "避免使用 Kafka",
                        "使用 Kafka"),
                Arguments.of(
                        "English missing-capability wording must not become positive capability",
                        "Lacked Kafka experience",
                        "Kafka experience"),
                Arguments.of(
                        "usage must not become proficiency",
                        "在项目中使用过 Redis",
                        "精通 Redis，具备丰富实践经验"),
                Arguments.of(
                        "proficiency must stay attached to the same technology",
                        "精通 Java，使用过 Redis",
                        "使用过 Java，精通 Redis"),
                Arguments.of(
                        "conjunctions must not allow proficiency to move between technologies",
                        "精通 Java 并使用过 Redis",
                        "使用过 Java 并精通 Redis"),
                Arguments.of(
                        "and-style conjunctions must not allow proficiency to move",
                        "精通 Java 与使用过 Redis",
                        "使用过 Java 与精通 Redis"),
                Arguments.of(
                        "implicit multi-fact text must not share proficiency levels",
                        "精通 Java 使用过 Redis",
                        "使用过 Java 精通 Redis"),
                Arguments.of(
                        "English proficiency must stay attached to the same technology",
                        "Expert in Java and used Redis",
                        "Used Java and expert in Redis"),
                Arguments.of(
                        "an existing number must not authorize a different unit or metric",
                        "开发 3 个接口",
                        "开发 3 个接口，性能提升 3%"),
                Arguments.of(
                        "existing number-unit pairs must stay attached to their original object",
                        "服务 3 个业务部门，参与 5 个接口开发",
                        "服务 5 个业务部门，参与 3 个接口开发"),
                Arguments.of(
                        "conjunctions must not allow number-unit pairs to move between objects",
                        "服务 3 个业务部门及 5 个接口",
                        "服务 5 个业务部门及 3 个接口"),
                Arguments.of(
                        "and-style conjunctions must not allow number-unit pairs to move",
                        "服务 3 个业务部门与维护 5 个接口",
                        "服务 5 个业务部门与维护 3 个接口"),
                Arguments.of(
                        "implicit multi-fact text must not share number contexts",
                        "服务 3 个业务部门维护 5 个接口",
                        "服务 5 个业务部门维护 3 个接口"),
                Arguments.of(
                        "slashes must not allow number-unit pairs to move between objects",
                        "服务 3 个业务部门/维护 5 个接口",
                        "服务 5 个业务部门/维护 3 个接口"),
                Arguments.of(
                        "a Chinese entity must not be introduced",
                        "参与订单服务开发",
                        "参与订单服务开发，客户为字节跳动"),
                Arguments.of(
                        "punctuation must not assemble unrelated Chinese fragments into a new entity",
                        "参与字典整理、节能改造、跳表测试、动态配置",
                        "字·节·跳·动"),
                Arguments.of(
                        "a lowercase unknown technology must not be introduced",
                        "负责订单服务开发",
                        "使用 quarkus 开发订单服务"),
                Arguments.of(
                        "a lowercase entity must not be introduced",
                        "负责客户服务",
                        "负责 amazon 客户服务"),
                Arguments.of(
                        "a Cyrillic entity must not bypass known script tokenization",
                        "负责订单服务开发",
                        "负责订单服务开发 Яндекс"),
                Arguments.of(
                        "responsibility must not escalate from participation to independent ownership",
                        "参与订单服务开发",
                        "独立实现订单服务"),
                Arguments.of(
                        "responsibility level must stay attached to the same work",
                        "主导支付服务开发，参与订单服务开发",
                        "参与支付服务开发，主导订单服务开发"),
                Arguments.of(
                        "conjunctions must not allow responsibility to move between work items",
                        "主导支付服务并参与订单服务",
                        "参与支付服务并主导订单服务"),
                Arguments.of(
                        "and-style conjunctions must not allow responsibility to move",
                        "主导支付服务与参与订单服务",
                        "参与支付服务与主导订单服务"),
                Arguments.of(
                        "implicit multi-fact text must not share responsibility levels",
                        "主导支付服务参与订单服务",
                        "参与支付服务主导订单服务"),
                Arguments.of(
                        "slashes must not allow responsibility to move between work items",
                        "主导支付服务/参与订单服务",
                        "参与支付服务/主导订单服务"),
                Arguments.of(
                        "pipes must not allow responsibility to move between work items",
                        "主导支付服务 | 参与订单服务",
                        "参与支付服务 | 主导订单服务"),
                Arguments.of(
                        "fact-bearing predicates must not be treated as interchangeable style",
                        "负责订单服务开发",
                        "负责订单服务设计与维护"),
                Arguments.of(
                        "participation must not become a completed result",
                        "参与订单服务开发",
                        "完成订单服务"),
                Arguments.of(
                        "a qualitative outcome must not be introduced",
                        "参与订单服务开发",
                        "参与订单服务开发，提升用户满意度"),
                Arguments.of(
                        "an existing outcome must not move to another work item",
                        "支付服务稳定性提升，但订单服务只做开发",
                        "支付服务只做开发，但订单服务稳定性提升"),
                Arguments.of(
                        "an implicit causal outcome must not be introduced",
                        "使用 Redis 处理订单缓存",
                        "通过 Redis 处理订单缓存，进而提升处理效率"),
                Arguments.of(
                        "a duration must not be introduced",
                        "负责订单服务开发",
                        "在两个月内完成订单服务开发"),
                Arguments.of(
                        "a geographic scope must not be introduced",
                        "负责订单服务开发",
                        "长期负责全国订单服务开发"),
                Arguments.of(
                        "time scope must not move to another work item",
                        "长期负责支付服务，但短期参与订单服务",
                        "短期负责支付服务，但长期参与订单服务"),
                Arguments.of(
                        "a zero-width character must not split a technology token",
                        "负责订单服务开发",
                        "使用 kaf\u200Bka 开发订单服务"),
                Arguments.of(
                        "a zero-width character must not split a Chinese capability",
                        "负责订单服务开发",
                        "负责订单微\u200B服务开发"));
    }

    @Test
    void shouldAllowReorderingAndGrammarAdjustment() {
        RewriteFactValidationResult result = validate(
                "使用 Redis 实现缓存功能，参与订单模块开发",
                "参与订单模块开发，并使用 Redis 实现缓存功能");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void shouldAllowRemovingUnrelatedContent() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发，平时喜欢打篮球",
                "负责订单服务的开发工作");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void shouldRejectFabricatedTechnology() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "负责订单服务开发，使用 Kafka 完成异步解耦");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_TECHNOLOGY);
    }

    @Test
    void shouldRejectFabricatedChineseTechnologyCapability() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "负责订单服务的微服务拆分与开发");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_TECHNOLOGY);
    }

    @Test
    void shouldRejectFabricatedMetric() {
        RewriteFactValidationResult result = validate(
                "负责订单服务性能优化",
                "负责订单服务性能优化，接口响应时间降低 40%");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM);
    }

    @Test
    void shouldRejectMultiplierClaimWithoutNumber() {
        RewriteFactValidationResult result = validate(
                "负责活动接口开发",
                "负责活动接口开发，活动期间吞吐量翻倍");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM);
    }

    @Test
    void shouldKeepExistingMetricInSynonymRewording() {
        RewriteFactValidationResult result = validate(
                "优化订单查询接口，耗时降低 40%",
                "对订单查询接口进行优化，耗时降低 40%");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void shouldRejectResponsibilityEscalationFromParticipantToOwner() {
        RewriteFactValidationResult result = validate(
                "参与订单服务开发",
                "主导订单服务的整体开发与架构设计");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.RESPONSIBILITY_ESCALATION);
    }

    @Test
    void shouldRejectResponsibilityEscalationFromDeveloperToLead() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "作为项目负责人主导订单服务的整体开发");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.RESPONSIBILITY_ESCALATION);
    }

    @Test
    void shouldRejectFabricatedAchievement() {
        RewriteFactValidationResult result = validate(
                "参与订单服务开发",
                "参与订单服务开发，项目获得公司技术创新奖");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_ACHIEVEMENT);
    }

    @Test
    void shouldRejectQualitativeAchievementInflation() {
        RewriteFactValidationResult result = validate(
                "负责订单服务性能优化",
                "负责订单服务性能优化，系统稳定性显著提升");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_ACHIEVEMENT);
    }

    @Test
    void shouldRejectFabricatedScope() {
        RewriteFactValidationResult result = validate(
                "参与订单服务开发",
                "参与订单服务开发，方案推广至全公司");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_SCOPE_OR_TIME);
    }

    @Test
    void shouldRejectFabricatedTimeFact() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "自 2021 年起负责订单服务开发");

        // 年份由数字规则或时间规则兜底拒绝，二者都满足 fail closed。
        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isIn(
                RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM,
                RewriteFactViolationCode.NEW_SCOPE_OR_TIME);
    }

    @Test
    void shouldRejectFabricatedEntity() {
        RewriteFactValidationResult result = validate(
                "参与订单服务开发",
                "参与订单服务开发，项目服务于 Amazon 平台");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_ENTITY);
    }

    @Test
    void shouldNotCompletePartialEvidenceIntoFullCapability() {
        // 原文只有“使用过 Redis”，不得被补成缓存设计经验等完整能力。
        RewriteFactValidationResult result = validate(
                "在项目中使用过 Redis",
                "具备 Redis 缓存架构设计经验，主导缓存方案落地");

        assertThat(result.passed()).isFalse();
    }

    @Test
    void shouldRejectBlankSuggestion() {
        RewriteFactValidationResult result = validate("负责订单服务开发", "   ");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.EMPTY_OR_BLANK);
    }

    @Test
    void shouldRejectOversizedSuggestion() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发", "负".repeat(RewriteFactValidatorImpl.MAX_SUGGESTED_LENGTH + 1));

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.OVERSIZED);
    }

    @Test
    void shouldClassifyElementIdentityLeakAsTechnicalSafetyFailure() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "负责订单服务开发 b-12");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.ELEMENT_IDENTITY_LEAK);
        assertThat(result.code().isTechnicalSafetyFailure()).isTrue();
    }

    @Test
    void shouldRejectControlCharacterAsTechnicalSafetyFailure() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "负责订单服务开发\u200B");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.CONTROL_CHARACTER);
        assertThat(result.code().isTechnicalSafetyFailure()).isTrue();
    }

    @Test
    void shouldRejectUnsupportedScriptAsTechnicalSafetyFailure() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "负责订单服务开发 Яндекс");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.UNSUPPORTED_SCRIPT);
        assertThat(result.code().isTechnicalSafetyFailure()).isTrue();
    }

    @Test
    void shouldRejectUuidLeak() {
        RewriteFactValidationResult result = validate(
                "负责订单服务开发",
                "负责订单服务开发 8627ff1c-95a7-4f3c-8341-af914e57d267");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.ELEMENT_IDENTITY_LEAK);
    }

    @Test
    void shouldRejectFullWidthMetricBypassAttempt() {
        // 全角数字经 NFKC 归一化后仍必须被识别为新增量化事实。
        RewriteFactValidationResult result = validate(
                "负责订单服务性能优化",
                "负责订单服务性能优化，接口响应时间降低 ４０％");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM);
    }

    @Test
    void shouldRejectTechTokenWithDigitsBypass() {
        RewriteFactValidationResult result = validate(
                "负责前端页面开发",
                "负责前端页面开发，熟练使用 Vue3 与 TypeScript");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_TECHNOLOGY);
    }

    @Test
    void shouldNotMoveFactsFromOtherBullets() {
        // 事实基线只有当前 Bullet 原文：即便“10 万 QPS”在别处出现过，也不得搬入本 Bullet。
        RewriteFactValidationResult result = validate(
                "使用 Redis 实现缓存",
                "使用 Redis 实现缓存，支撑 10 万 QPS");

        assertThat(result.passed()).isFalse();
    }

    @Test
    void shouldRejectNewPercentageWhenOriginalOnlyContainsYearNumber() {
        // 数字按 token 精确匹配：原文“2023”不得被解释为允许“20%”。
        RewriteFactValidationResult result = validate(
                "2023 年参与订单项目开发",
                "2023 年参与订单项目开发，接口性能提升 20%");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM);
    }

    @Test
    void shouldRejectChineseNumeralQuantification() {
        RewriteFactValidationResult result = validate(
                "负责活动接口开发",
                "负责活动接口开发，吞吐量提升三倍");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM);
    }

    @Test
    void shouldRejectChinesePercentExpression() {
        RewriteFactValidationResult result = validate(
                "负责订单服务性能优化",
                "负责订单服务性能优化，接口耗时下降百分之三十");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_QUANTITATIVE_CLAIM);
    }

    @Test
    void shouldRejectLowercaseTechnologyNotInOriginal() {
        RewriteFactValidationResult result = validate(
                "负责配置管理模块开发",
                "负责配置管理模块开发，使用 etcd 存储配置");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_TECHNOLOGY);
    }

    @Test
    void shouldRejectEscalationByLeadingTeam() {
        RewriteFactValidationResult result = validate(
                "参与平台开发",
                "主持平台开发并带队完成交付");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.RESPONSIBILITY_ESCALATION);
    }

    @Test
    void shouldRejectNovelKnowledgeCapabilityTerms() {
        RewriteFactValidationResult result = validate(
                "参与问答功能开发",
                "参与问答功能开发，建设知识库与向量能力");

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo(RewriteFactViolationCode.NEW_TECHNOLOGY);
    }
}
