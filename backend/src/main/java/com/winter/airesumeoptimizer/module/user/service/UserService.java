package com.winter.airesumeoptimizer.module.user.service;

import com.winter.airesumeoptimizer.module.user.dto.UpdateUserProfileRequestDTO;
import com.winter.airesumeoptimizer.module.user.vo.UserProfileVO;

public interface UserService {

    UserProfileVO getCurrentUserProfile(Long userId);

    UserProfileVO updateCurrentUserProfile(Long userId, UpdateUserProfileRequestDTO request);
}
