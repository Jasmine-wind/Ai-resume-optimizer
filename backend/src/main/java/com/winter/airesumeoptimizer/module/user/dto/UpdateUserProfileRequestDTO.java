package com.winter.airesumeoptimizer.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "当前用户个人资料更新请求")
public class UpdateUserProfileRequestDTO {

    @Schema(description = "显示名称；空字符串会恢复为用户名", example = "李明", maxLength = 50)
    @Size(max = 50, message = "显示名称长度不能超过50个字符")
    private String nickname;

    public void setNickname(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }
}
