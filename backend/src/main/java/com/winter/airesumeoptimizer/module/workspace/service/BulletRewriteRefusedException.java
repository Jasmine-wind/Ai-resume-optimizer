package com.winter.airesumeoptimizer.module.workspace.service;

/**
 * AI 明确拒绝或没有返回可用候选。属于生成结果的正常失败，不是内容事实审查结果。
 */
public class BulletRewriteRefusedException extends RuntimeException {

    public BulletRewriteRefusedException(String message) {
        super(message);
    }
}
