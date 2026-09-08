package com.winter.airesumeoptimizer.module.workspace.service.impl;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.ai.PromptTemplateService;
import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceAnalysisResultVO;
import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceRequirementVO;
import com.winter.airesumeoptimizer.module.evidence.vo.RequirementEvidenceVO;
import com.winter.airesumeoptimizer.module.workspace.dto.BulletRewritePromptDTO;
import com.winter.airesumeoptimizer.module.workspace.enums.BulletSuggestIntent;
import com.winter.airesumeoptimizer.module.workspace.service.BulletRewritePromptService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BulletRewritePromptServiceImpl implements BulletRewritePromptService {

    private static final String SYSTEM_TEMPLATE_PATH = "prompts/bullet-rewrite-system-v2.md";
    private static final String USER_TEMPLATE_PATH = "prompts/bullet-rewrite-v2.md";
    private static final int MAX_REQUIREMENT_CONTEXT_LENGTH = 6000;
    private static final int MAX_ORIGINAL_TEXT_LENGTH = 4000;
    private static final int MAX_INSTRUCTION_LENGTH = 500;
    private static final String MATCHED = "MATCHED";
    private static final String PARTIAL_EVIDENCE = "PARTIAL_EVIDENCE";

    private final PromptTemplateService promptTemplateService;

    public BulletRewritePromptServiceImpl(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public BulletRewritePromptDTO buildPrompt(
            BulletSuggestIntent intent,
            String userInstruction,
            String originalText,
            EvidenceAnalysisResultVO evidenceAnalysis) {
        if (intent == null) {
            throw new BusinessException(400, "缺少改写意图");
        }
        if (originalText == null || originalText.isBlank()) {
            throw new BusinessException(400, "要点原文不能为空");
        }

        String instruction = userInstruction == null ? "" : userInstruction.strip();
        if (instruction.length() > MAX_INSTRUCTION_LENGTH) {
            instruction = instruction.substring(0, MAX_INSTRUCTION_LENGTH);
        }
        String bullet = originalText.strip();
        if (bullet.length() > MAX_ORIGINAL_TEXT_LENGTH) {
            bullet = bullet.substring(0, MAX_ORIGINAL_TEXT_LENGTH);
        }

        // PromptTemplateService 单遍替换：占位符值不会被二次展开，顺序不再有安全含义。
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("intentDescription", describeIntent(intent));
        variables.put("userInstruction", instruction.isBlank() ? "（无）" : instruction);
        variables.put("requirementContext", buildRequirementContext(evidenceAnalysis));
        variables.put("originalText", bullet);

        return BulletRewritePromptDTO.builder()
                .promptVersion(PROMPT_VERSION)
                .systemPolicy(promptTemplateService.render(SYSTEM_TEMPLATE_PATH, Map.of()))
                .userContent(promptTemplateService.render(USER_TEMPLATE_PATH, variables))
                .build();
    }

    /**
     * 只纳入 MATCHED / PARTIAL_EVIDENCE 的要求与其 SOURCE 证据引用；
     * NO_EVIDENCE 不得进入改写上下文，避免 AI 为缺口补全事实。
     */
    private String buildRequirementContext(EvidenceAnalysisResultVO evidenceAnalysis) {
        if (evidenceAnalysis == null || evidenceAnalysis.getRequirements() == null) {
            return "（无）";
        }
        StringBuilder context = new StringBuilder();
        for (EvidenceRequirementVO requirement : evidenceAnalysis.getRequirements()) {
            if (!MATCHED.equals(requirement.getMatchLevel())
                    && !PARTIAL_EVIDENCE.equals(requirement.getMatchLevel())) {
                continue;
            }
            if (context.length() > MAX_REQUIREMENT_CONTEXT_LENGTH) {
                context.append("\n[岗位参考内容过长，已截断]");
                break;
            }
            context.append("- [")
                    .append(MATCHED.equals(requirement.getMatchLevel()) ? "已有优势" : "建议完善")
                    .append("] 要求：")
                    .append(text(requirement.getRequirementText()));
            for (RequirementEvidenceVO evidence : requirement.getEvidences()) {
                context.append("；材料证据：");
                if (evidence.getSectionLabel() != null && !evidence.getSectionLabel().isBlank()) {
                    context.append(text(evidence.getSectionLabel())).append(" ");
                }
                context.append("「").append(text(evidence.getEvidenceText())).append("」");
            }
            context.append('\n');
        }
        return context.isEmpty() ? "（无）" : context.toString().strip();
    }

    private String describeIntent(BulletSuggestIntent intent) {
        return switch (intent) {
            case JOB_TARGETED -> "岗位定向优化：在不新增事实的前提下，让表达更贴近目标岗位的关注点";
            case SIMPLIFY -> "精简：去掉冗余和重复表达，保留全部事实";
            case TECHNICAL_DEPTH -> "强化技术深度：改善技术表达的专业性与准确性，不得新增技术事实";
            case HIGHLIGHT_OUTCOME -> "突出成果：改善成果表达的组织方式，不得新增量化结果或成果";
            case CUSTOM -> "自定义要求：在满足平台真实性约束的前提下尽量贴近用户本次要求";
        };
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }
}
