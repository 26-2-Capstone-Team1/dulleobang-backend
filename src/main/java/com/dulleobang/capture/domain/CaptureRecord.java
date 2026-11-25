package com.dulleobang.capture.domain;

import java.time.Instant;
import java.util.UUID;

public record CaptureRecord(
    UUID id,
    UUID owner,
    String targetType,
    UUID targetId,
    UUID session,
    UUID archive,
    UUID latestJob,
    Instant createdAt) {}
