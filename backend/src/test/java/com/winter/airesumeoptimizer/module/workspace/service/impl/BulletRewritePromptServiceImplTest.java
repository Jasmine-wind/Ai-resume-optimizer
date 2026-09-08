package com.winter.airesumeoptimizer.module.workspace.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.ai.PromptTemplateService;
import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceAnalysisResultVO;
import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceRequirementVO;
import com.winter.airesumeoptimizer.module.evidence.vo.RequirementEvidenceVO;
import com.winter.airesumeoptimizer.module.workspace.dto.BulletRewritePromptDTO;
import com.winter.airesumeoptimizer.module.workspace.enums.BulletSuggestIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Prompt 组装约束：SYSTEM 只承载可信策略；NO_EVIDENCE 进入 Rewrite 上下文但明确不是用户事实。
 */
class BulletRewritePromptServiceImplTest {

    private final BulletRewritePromptServiceImpl service =
            new BulletRewritePromptServiceImpl(new PromptTemplateService());

    @Test
    void v3PromptShouldAllowCandidatesAndGroundReasonsInActualChanges() {
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.JOB_TARGETED, null, "负责订单服务开发", evidenceAnalysis());

        assertThat(prompt.getPromptVersion()).isEqualTo("bullet_rewrite_v3");
        assertThat(prompt.getSystemPolicy()).contains("候选");
        assertThat(prompt.getSystemPolicy()).contains("用户必须看到原文、建议版本、Diff");
        assertThat(prompt.getSystemPolicy()).contains("不要为了生成结果而生成结果");
        assertThat(prompt.getSystemPolicy()).contains("只增加“并 / 且 / 并且 / 以及”等连接词");
        assertThat(prompt.getSystemPolicy()).contains("reason 必须对应");
        assertThat(prompt.getSystemPolicy()).contains("当前表述已经清楚，没有足够有价值的改写");
        assertThat(prompt.getSystemPolicy()).contains("请确认真实性");
    }

    @Test
    void systemPolicyKeepsOriginalBulletAsOnlyFactClosure() {
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.JOB_TARGETED, null, "负责订单服务开发", evidenceAnalysis());

        assertThat(prompt.getSystemPolicy()).contains("可以根据用户要求、岗位要求和 Evidence 分析提出原文没有明确写出的候选");
        assertThat(prompt.getUserContent()).contains("被改写要点原文");
        assertThat(prompt.getUserContent()).contains("当前材料未体现");
        assertThat(prompt.getUserContent()).contains("Kafka 消息队列经验");
    }

    @Test
    void systemPolicyShouldCarryPlatformConstraintsOnly() {
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.JOB_TARGETED, null, "负责订单服务开发", evidenceAnalysis());

        assertThat(prompt.getSystemPolicy()).contains("平台真实性约束");
        assertThat(prompt.getSystemPolicy()).contains("事实闭包");
        // 不可信数据不得混入 SYSTEM 消息。
        assertThat(prompt.getSystemPolicy()).doesNotContain("负责订单服务开发");
        assertThat(prompt.getSystemPolicy()).doesNotContain("熟悉 Redis");
    }

    @Test
    void userContentShouldFenceUntrustedData() {
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.CUSTOM, "突出后端经验", "负责订单服务开发", evidenceAnalysis());

        assertThat(prompt.getUserContent()).contains("数据区开始");
        assertThat(prompt.getUserContent()).contains("不可信数据");
        assertThat(prompt.getUserContent()).contains("负责订单服务开发");
        assertThat(prompt.getUserContent()).contains("突出后端经验");
    }

    @Test
    void noEvidenceRequirementEntersContextAsUnconfirmedCandidateDirection() {
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.JOB_TARGETED, null, "负责订单服务开发", evidenceAnalysis());

        assertThat(prompt.getUserContent()).contains("熟悉 Redis");
        assertThat(prompt.getUserContent()).contains("具备 Kafka 消息队列经验");
        assertThat(prompt.getUserContent()).contains("这是岗位要求，可作为候选补充方向，不是已确认的用户事实");
        assertThat(prompt.getUserContent()).contains("当前材料未体现");
    }

    @Test
    void matchedPartialAndNoEvidenceRequirementsShouldBeIncludedWithDistinctLabels() {
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.JOB_TARGETED, null, "负责订单服务开发", evidenceAnalysis());

        assertThat(prompt.getUserContent()).contains("已有优势");
        assertThat(prompt.getUserContent()).contains("建议完善");
        assertThat(prompt.getUserContent()).contains("「使用过 Redis」");
    }

    @Test
    void blankOriginalTextShouldBeRejected() {
        assertThatThrownBy(() -> service.buildPrompt(
                BulletSuggestIntent.SIMPLIFY, null, "   ", evidenceAnalysis()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void untrustedValuesMustNotExpandOtherPlaceholders() {
        // 单遍替换回归：不可信值中的 {{...}} 字面量必须原样保留，杜绝二次展开。
        BulletRewritePromptDTO prompt = service.buildPrompt(
                BulletSuggestIntent.CUSTOM,
                "{{originalText}}{{requirementContext}}",
                "负责订单服务开发",
                evidenceAnalysis());

        assertThat(prompt.getUserContent()).contains("{{originalText}}{{requirementContext}}");
        // 原文只应出现在事实闭包区域一次。
        int firstIndex = prompt.getUserContent().indexOf("负责订单服务开发");
        assertThat(firstIndex).isNotNegative();
        assertThat(prompt.getUserContent().indexOf("负责订单服务开发", firstIndex + 1)).isNegative();
    }

    private EvidenceAnalysisResultVO evidenceAnalysis() {
        return EvidenceAnalysisResultVO.builder()
                .evidenceAnalysisId(9L)
                .matchedCount(1)
                .partialEvidenceCount(1)
                .noEvidenceCount(1)
                .requirements(List.of(
                        EvidenceRequirementVO.builder()
                                .evidenceRequirementId(1L)
                                .requirementText("熟悉 Redis")
                                .matchLevel("MATCHED")
                                .evidences(List.of(RequirementEvidenceVO.builder()
                                        .requirementEvidenceId(11L)
                                        .sectionLabel("技能")
                                        .evidenceText("使用过 Redis")
                                        .supportLevel("SUFFICIENT")
                                        .build()))
                                .build(),
                        EvidenceRequirementVO.builder()
                                .evidenceRequirementId(2L)
                                .requirementText("后端接口开发")
                                .matchLevel("PARTIAL_EVIDENCE")
                                .evidences(List.of())
                                .build(),
                        EvidenceRequirementVO.builder()
                                .evidenceRequirementId(3L)
                                .requirementText("具备 Kafka 消息队列经验")
                                .matchLevel("NO_EVIDENCE")
                                .evidences(List.of())
                                .build()))
                .build();
    }
}
