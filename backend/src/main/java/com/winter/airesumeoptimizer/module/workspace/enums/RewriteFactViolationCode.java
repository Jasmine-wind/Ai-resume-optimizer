package com.winter.airesumeoptimizer.module.workspace.enums;

/**
 * Rewrite 内容审查与技术安全结果类型。
 * 内容变化是用户确认提示，不是建议状态的硬拦截；技术安全结果仍然 fail closed。
 */
public enum RewriteFactViolationCode {

    /** 校验通过。 */
    OK,

    /** 建议文本为空或只有空白。 */
    EMPTY_OR_BLANK,

    /** 建议文本超过 Bullet 长度上限。 */
    OVERSIZED,

    /** 出现原文没有的数字 / 量化结果 / 倍数声明。 */
    NEW_QUANTITATIVE_CLAIM,

    /** 出现原文没有的技术、框架、工具或系统名称。 */
    NEW_TECHNOLOGY,

    /** 出现原文没有的实体（公司、产品、项目等专有名词）。 */
    NEW_ENTITY,

    /** 责任级别被升级，例如参与改为主导、开发改为负责人。 */
    RESPONSIBILITY_ESCALATION,

    /** 出现原文没有的成果、奖项、效果结论。 */
    NEW_ACHIEVEMENT,

    /** 出现原文没有的范围或时间事实，例如年份、公司级范围。 */
    NEW_SCOPE_OR_TIME,

    /** 出现疑似元素 ID / UUID，AI 不得生成结构化身份。 */
    ELEMENT_IDENTITY_LEAK,

    /** 无法自动判断内容变化，提示用户核对真实性。 */
    UNDETERMINED,

    /** 输出包含不可见格式控制字符，技术上不安全。 */
    CONTROL_CHARACTER,

    /** 输出包含未支持的字符脚本，无法安全处理。 */
    UNSUPPORTED_SCRIPT;

    public boolean isTechnicalSafetyFailure() {
        return switch (this) {
            case EMPTY_OR_BLANK, OVERSIZED, ELEMENT_IDENTITY_LEAK, CONTROL_CHARACTER, UNSUPPORTED_SCRIPT -> true;
            default -> false;
        };
    }
}
