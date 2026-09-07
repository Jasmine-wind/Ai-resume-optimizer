package com.winter.airesumeoptimizer.module.user.controller;

import com.winter.airesumeoptimizer.common.result.Result;
import com.winter.airesumeoptimizer.module.user.dto.UpdateUserProfileRequestDTO;
import com.winter.airesumeoptimizer.module.user.service.UserService;
import com.winter.airesumeoptimizer.module.user.vo.UserProfileVO;
import com.winter.airesumeoptimizer.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "User", description = "当前用户信息接口")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PatchMapping("/me")
    @Operation(
            summary = "更新当前用户显示名称",
            description = "只允许更新 JWT 当前用户的 nickname；用户名和邮箱不可修改",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<UserProfileVO> updateCurrentUser(
            @Valid @RequestBody UpdateUserProfileRequestDTO request,
            Authentication authentication) {
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) authentication.getPrincipal();
        return Result.success(userService.updateCurrentUserProfile(authenticatedUser.getUserId(), request));
    }

    @GetMapping("/me")
    @Operation(
            summary = "获取当前用户",
            description = "根据 JWT 获取当前登录用户资料",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<UserProfileVO> getCurrentUser(Authentication authentication) {
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) authentication.getPrincipal();
        return Result.success(userService.getCurrentUserProfile(authenticatedUser.getUserId()));
    }
}
