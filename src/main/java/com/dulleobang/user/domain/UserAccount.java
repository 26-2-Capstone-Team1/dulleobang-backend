package com.dulleobang.user.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserAccount(
    UUID userId,
    String email,
    String displayName,
    List<String> roles,
    List<String> authProviders,
    String phone,
    boolean isPhonePublic,
    long version,
    Instant createdAt) {}
