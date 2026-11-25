package com.dulleobang.furniture.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record FurnitureItem(
    UUID id,
    UUID owner,
    String name,
    String category,
    JsonNode measuredDimensions,
    String status,
    long version,
    JsonNode model,
    UUID activeJob,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {}
