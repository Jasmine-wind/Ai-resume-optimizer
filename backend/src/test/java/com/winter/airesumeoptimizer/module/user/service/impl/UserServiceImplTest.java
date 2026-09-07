package com.winter.airesumeoptimizer.module.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.module.user.dto.UpdateUserProfileRequestDTO;
import com.winter.airesumeoptimizer.module.user.entity.User;
import com.winter.airesumeoptimizer.module.user.mapper.UserMapper;
import com.winter.airesumeoptimizer.module.user.vo.UserProfileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserServiceImplTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserServiceImpl service = new UserServiceImpl(userMapper);
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);
        user.setUsername("winter");
        user.setEmail("winter@example.com");
        user.setNickname("Winter");
        when(userMapper.selectById(7L)).thenReturn(user);
        when(userMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void updateTrimsNicknameAndReturnsUpdatedProfile() {
        UpdateUserProfileRequestDTO request = new UpdateUserProfileRequestDTO();
        request.setNickname("  李明  ");

        UserProfileVO result = service.updateCurrentUserProfile(7L, request);

        assertThat(result.getNickname()).isEqualTo("李明");
        assertThat(result.getUsername()).isEqualTo("winter");
        verify(userMapper).update(any(), any());
    }

    @Test
    void blankNicknameBecomesNull() {
        UpdateUserProfileRequestDTO request = new UpdateUserProfileRequestDTO();
        request.setNickname("   ");

        UserProfileVO result = service.updateCurrentUserProfile(7L, request);

        assertThat(result.getNickname()).isNull();
    }

    @Test
    void missingCurrentUserReturnsNotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);
        UpdateUserProfileRequestDTO request = new UpdateUserProfileRequestDTO();
        request.setNickname("李明");

        assertThatThrownBy(() -> service.updateCurrentUserProfile(999L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(404);
    }
}
