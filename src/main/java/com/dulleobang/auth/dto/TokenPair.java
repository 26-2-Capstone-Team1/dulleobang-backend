package com.dulleobang.auth.dto;

import com.dulleobang.user.dto.UserResponse;

public record TokenPair(
    String accessToken,
    String refreshToken,
    String tokenType,
    int expiresIn,
    int refreshExpiresIn,
    UserResponse user) {}
