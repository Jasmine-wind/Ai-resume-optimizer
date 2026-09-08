package com.winter.airesumeoptimizer.module.workspace.service;

import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceAnalysisResultVO;
import com.winter.airesumeoptimizer.module.workspace.dto.BulletRewritePromptDTO;
import com.winter.airesumeoptimizer.module.workspace.enums.BulletSuggestIntent;

/**
 * Bullet 改写 Prompt 组装边界。
 *
 * <p>SYSTEM 消息只包含平台可信策略；USER 消息承载全部不可信数据并明确标注数据区。
 * 岗位上下文纳入正式证据分析的三态要求；NO_EVIDENCE 仅作为标记为“当前材料未体现”的候选补充方向，
 * 不被当作用户已确认事实。
 */
public interface BulletRewritePromptService {

    String PROMPT_VERSION = "bullet_rewrite_v3";

    BulletRewritePromptDTO buildPrompt(
            BulletSuggestIntent intent,
            String userInstruction,
            String originalText,
            EvidenceAnalysisResultVO evidenceAnalysis);
}
