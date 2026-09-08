package com.winter.airesumeoptimizer.module.workspace.dto;

import com.winter.airesumeoptimizer.module.workspace.enums.RewriteFactViolationCode;

/**
 * Rewrite 内容审查结果。事实变化可作为 advisory；技术安全失败仍不可进入 READY。
 */
public record RewriteFactValidationResult(
        boolean passed,
        RewriteFactViolationCode code,
        String message) {

    public static RewriteFactValidationResult pass() {
        return new RewriteFactValidationResult(true, RewriteFactViolationCode.OK, null);
    }

    public static RewriteFactValidationResult fail(RewriteFactViolationCode code, String message) {
        return new RewriteFactValidationResult(false, code, message);
    }
}
