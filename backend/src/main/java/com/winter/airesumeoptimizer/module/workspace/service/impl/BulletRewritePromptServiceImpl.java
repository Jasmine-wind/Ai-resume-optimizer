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

    private static final String SYSTEM_TEMPLATE_PATH = "prompts/bullet-rewrite-system-v3.md";
    private static final String USER_TEMPLATE_PATH = "prompts/bullet-rewrite-v3.md";
    private static final int MAX_REQUIREMENT_CONTEXT_LENGTH = 6000;
    private static final int MAX_ORIGINAL_TEXT_LENGTH = 4000;
    private static final int MAX_INSTRUCTION_LENGTH = 500;
    private static final String MATCHED = "MATCHED";
    private static final String PARTIAL_EVIDENCE = "PARTIAL_EVIDENCE";
    private static final String NO_EVIDENCE = "NO_EVIDENCE";

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
     * 三类岗位要求都可作为改写参考；NO_EVIDENCE 明确标注为当前材料未体现，不能被当作已确认事实。
     */
    private String buildRequirementContext(EvidenceAnalysisResultVO evidenceAnalysis) {
        if (evidenceAnalysis == null || evidenceAnalysis.getRequirements() == null) {
            return "（无）";
        }
        StringBuilder context = new StringBuilder();
        for (EvidenceRequirementVO requirement : evidenceAnalysis.getRequirements()) {
            String matchLevel = requirement.getMatchLevel();
            if (!MATCHED.equals(matchLevel)
                    && !PARTIAL_EVIDENCE.equals(matchLevel)
                    && !NO_EVIDENCE.equals(matchLevel)) {
                continue;
            }
            if (context.length() > MAX_REQUIREMENT_CONTEXT_LENGTH) {
                context.append("\n[岗位参考内容过长，已截断]");
                break;
            }
            String status = MATCHED.equals(matchLevel)
                    ? "已有优势"
                    : PARTIAL_EVIDENCE.equals(matchLevel) ? "建议完善" : "当前材料未体现";
            context.append("- [")
                    .append(status)
                    .append("] 要求：")
                    .append(text(requirement.getRequirementText()));
            if (NO_EVIDENCE.equals(matchLevel)) {
                context.append("；这是岗位要求，可作为候选补充方向，不是已确认的用户事实");
            }
            if (requirement.getEvidences() == null) {
                context.append('\n');
                continue;
            }
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
            case JOB_TARGETED -> "岗位定向优化：根据岗位要求调整表达，也可以提出原文未写明的候选内容；新增内容必须在理由中提醒用户核对真实性";
            case SIMPLIFY -> "精简：以删减、压缩和重组为主，一般不主动增加事实";
            case TECHNICAL_DEPTH -> "强化技术深度：可以提出更多技术细节候选；如果原文没有，理由必须提醒用户确认真实性";
            case HIGHLIGHT_OUTCOME -> "突出成果：可以提出成果表达候选；新增结果或数字必须在理由中提醒用户核对";
            case CUSTOM -> "自定义要求：优先满足用户明确要求；用户是最终真实性确认者，新增内容必须具体说明并提醒核对";
        };
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }
}
