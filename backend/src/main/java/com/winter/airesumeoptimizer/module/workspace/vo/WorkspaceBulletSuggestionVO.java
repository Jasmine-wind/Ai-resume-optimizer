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

    /** 拒绝码：改写通过事实校验但没有足够可辨认的表达价值。 */
    public static final String REJECT_CODE_LOW_VALUE_CHANGE = "LOW_VALUE_CHANGE";

    @Schema(description = "客户端生成的请求 UUID，原样回传")
    private String requestId;

    @Schema(description = "READY 可审查采纳；REJECTED 表示事实校验或 AI 拒绝，不得采纳")
    private String state;

    @Schema(description = "服务端校验建议时使用的 TARGET 内容版本号")
    private Long baseRevision;

    @Schema(description = "被改写的 Bullet ID")
    private String bulletId;

    @Schema(description = "服务端确认的 Bullet 原文（事实闭包基线）")
    private String originalText;

    @Schema(description = "通过事实校验的建议文本；REJECTED 时为 null")
    private String suggestedText;

    @Schema(description = "AI 给出的修改原因；REJECTED 时为 null")
    private String reason;

    @Schema(description = "拒绝码：事实校验违规类型、AI_REFUSED 或 LOW_VALUE_CHANGE；READY 时为 null")
    private String rejectCode;

    @Schema(description = "面向用户的拒绝说明；READY 时为 null")
    private String rejectMessage;

    @Schema(description = "生成本建议的模型名")
    private String modelName;
}
