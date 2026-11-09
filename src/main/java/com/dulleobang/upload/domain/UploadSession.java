package com.dulleobang.upload.domain;

import java.time.Instant;
import java.util.UUID;

public record UploadSession(
    UUID id,
    UUID owner,
    String purpose,
    String targetType,
    UUID targetId,
    String status,
    Instant createdAt,
    Instant expiresAt) {}
