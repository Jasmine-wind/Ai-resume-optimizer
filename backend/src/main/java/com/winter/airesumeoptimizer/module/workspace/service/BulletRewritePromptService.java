package com.winter.airesumeoptimizer.module.workspace.service;

import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceAnalysisResultVO;
import com.winter.airesumeoptimizer.module.workspace.dto.BulletRewritePromptDTO;
import com.winter.airesumeoptimizer.module.workspace.enums.BulletSuggestIntent;

/**
 * Bullet 改写 Prompt 组装边界。
 *
 * <p>SYSTEM 消息只包含平台可信策略；USER 消息承载全部不可信数据并明确标注数据区。
 * 岗位上下文只纳入正式证据分析中 MATCHED / PARTIAL_EVIDENCE 的要求与证据，
 * NO_EVIDENCE 要求不得进入 Rewrite。
 */
public interface BulletRewritePromptService {

    String PROMPT_VERSION = "bullet_rewrite_v2";

    BulletRewritePromptDTO buildPrompt(
            BulletSuggestIntent intent,
            String userInstruction,
            String originalText,
            EvidenceAnalysisResultVO evidenceAnalysis);
}
