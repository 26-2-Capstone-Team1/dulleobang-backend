package com.dulleobang.job.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record JobRecord(
    UUID id,
    UUID owner,
    String type,
    String targetType,
    UUID targetId,
    UUID listingId,
    UUID captureId,
    String status,
    int attempt,
    int progress,
    String stage,
    JsonNode result,
    JsonNode error,
    UUID retryOf,
    Instant createdAt,
    Instant updatedAt,
    Instant finishedAt,
    String leaseHash,
    Instant leaseExpiresAt,
    Instant attemptStartedAt,
    String worker,
    Instant dueAt) {
  public boolean active() {
    return java.util.Set.of("QUEUED", "RUNNING", "RETRY_WAIT").contains(status);
  }
}
