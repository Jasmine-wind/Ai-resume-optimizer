package com.winter.airesumeoptimizer.module.workspace.enums;

/**
 * 单 Bullet 岗位定向改写意图。所有意图都受同一技术安全校验、用户审查和显式 Apply 约束。
 */
public enum BulletSuggestIntent {

    /** 岗位定向优化：强调与目标岗位相关的表达。 */
    JOB_TARGETED,

    /** 精简：去掉冗余表达。 */
    SIMPLIFY,

    /** 强化技术深度：可以提出更多技术细节候选，由用户确认真实性。 */
    TECHNICAL_DEPTH,

    /** 突出成果：可以提出成果或量化表达候选，由用户确认真实性。 */
    HIGHLIGHT_OUTCOME,

    /** 自定义要求：附带用户本次要求（不可信输入，不得覆盖平台约束）。 */
    CUSTOM
}
