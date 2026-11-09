package com.dulleobang.upload.domain;

import java.time.Instant;
import java.util.UUID;

public record AssetFile(
    UUID id,
    UUID owner,
    UUID session,
    String targetType,
    UUID targetId,
    String purpose,
    UUID clientId,
    String name,
    String contentType,
    long byteSize,
    String sha256,
    String format,
    String role,
    int ordinal,
    boolean stored,
    boolean verified,
    UUID derivative,
    Instant createdAt) {}
