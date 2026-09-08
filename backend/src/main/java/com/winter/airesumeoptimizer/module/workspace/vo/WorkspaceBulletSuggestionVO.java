package com.winter.airesumeoptimizer.module.workspace.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 单 Bullet AI 改写建议结果。
 *
 * <p>建议只存在于当前会话，不落库；Apply / Reject / Regenerate 由前端驱动，
 * 服务端不保存建议、历史或变更事件，也不提供任何服务端 Apply 写入链路。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "单 Bullet 岗位定向改写建议结果")
public class WorkspaceBulletSuggestionVO {

    public static final String STATE_READY = "READY";
    public static final String STATE_REJECTED = "REJECTED";

    /** 拒绝码：AI 明确拒绝或疑似拒绝话术。 */
    public static final String REJECT_CODE_REFUSED = "AI_REFUSED";

    /** 拒绝码：建议没有足够可辨认的表达价值，属于质量 Gate 而非真实性判断。 */
    public static final String REJECT_CODE_LOW_VALUE_CHANGE = "LOW_VALUE_CHANGE";

    @Schema(description = "客户端生成的请求 UUID，原样回传")
    private String requestId;

    @Schema(description = "READY 可审查并由用户采纳；REJECTED 仅表示技术安全问题、AI 拒绝或低价值变化")
    private String state;

    @Schema(description = "服务端校验建议时使用的 TARGET 内容版本号")
    private Long baseRevision;

    @Schema(description = "被改写的 Bullet ID")
    private String bulletId;

    @Schema(description = "服务端确认的 Bullet 原文（事实闭包基线）")
    private String originalText;

    @Schema(description = "AI 候选建议文本；技术性 REJECTED 时为 null")
    private String suggestedText;

    @Schema(description = "AI 给出的具体修改原因；技术性 REJECTED 时为 null")
    private String reason;

    @Schema(description = "内容审查提示码；建议可采纳时也可能携带")
    private String reviewCode;

    @Schema(description = "面向用户的真实性核对提示；无提示时为 null")
    private String reviewMessage;

    @Schema(description = "拒绝码：仅用于技术安全问题、AI_REFUSED 或 LOW_VALUE_CHANGE")
    private String rejectCode;

    @Schema(description = "面向用户的拒绝说明；READY 时为 null")
    private String rejectMessage;

    @Schema(description = "生成本建议的模型名")
    private String modelName;
}
