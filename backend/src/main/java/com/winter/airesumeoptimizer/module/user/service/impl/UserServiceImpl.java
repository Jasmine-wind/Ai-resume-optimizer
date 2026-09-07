package com.winter.airesumeoptimizer.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.module.user.dto.UpdateUserProfileRequestDTO;
import com.winter.airesumeoptimizer.module.user.entity.User;
import com.winter.airesumeoptimizer.module.user.mapper.UserMapper;
import com.winter.airesumeoptimizer.module.user.service.UserService;
import com.winter.airesumeoptimizer.module.user.vo.UserProfileVO;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserProfileVO getCurrentUserProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        return toProfile(user);
    }

    @Override
    public UserProfileVO updateCurrentUserProfile(Long userId, UpdateUserProfileRequestDTO request) {
        if (request == null) {
            throw new BusinessException(400, "个人资料不能为空");
        }
        User current = userMapper.selectById(userId);
        if (current == null) {
            throw new BusinessException(404, "用户不存在");
        }
        String nickname = request.getNickname() == null ? null : request.getNickname().strip();
        if (nickname != null && nickname.isBlank()) {
            nickname = null;
        }
        int updated = userMapper.update(
                null,
                new UpdateWrapper<User>()
                        .eq("id", userId)
                        .set("nickname", nickname)
                        .set("updated_at", java.time.LocalDateTime.now()));
        if (updated != 1) {
            throw new BusinessException(404, "用户不存在");
        }
        current.setNickname(nickname);
        return toProfile(current);
    }

    private UserProfileVO toProfile(User user) {
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
