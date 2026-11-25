package com.dulleobang.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSession(UUID sessionId, UUID userId, Instant expiresAt, boolean revoked) {}
