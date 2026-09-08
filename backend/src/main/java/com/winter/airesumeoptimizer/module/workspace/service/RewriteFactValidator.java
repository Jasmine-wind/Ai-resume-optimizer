package com.winter.airesumeoptimizer.module.workspace.service;

import com.winter.airesumeoptimizer.module.workspace.dto.RewriteFactValidationResult;

/**
 * Rewrite 专用内容审查 seam。
 *
 * <p>事实基线只取被改写 Bullet 自身的原文，不跨 Bullet 搬运事实。实现识别新增或升级的实体、
 * 技术、数字/量化结果、责任级别、成果、因果、范围和时间，供服务层生成用户 review advisory。
 * 空值、超长、内部身份泄露、控制字符和不支持脚本等技术问题仍必须 fail closed。
 * 不得把第二次 LLM 调用作为唯一真实性裁判。
 */
public interface RewriteFactValidator {

    RewriteFactValidationResult validate(String originalText, String suggestedText);
}
