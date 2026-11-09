package com.dulleobang.user.dto;

import java.util.List;
import java.util.UUID;

public record UserResponse(
    UUID userId,
    String email,
    String displayName,
    List<String> roles,
    long version,
    String createdAt,
    List<String> authProviders,
    String phone,
    boolean isPhonePublic) {}
