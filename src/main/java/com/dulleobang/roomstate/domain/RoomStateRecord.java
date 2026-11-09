package com.dulleobang.roomstate.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

public record RoomStateRecord(
    UUID id,
    UUID owner,
    UUID listingId,
    int sourceSpaceVersion,
    int sourceObjectsVersion,
    List<UUID> removedObjectIds,
    String status,
    UUID jobId,
    JsonNode bundle,
    JsonNode sourceBundle,
    Instant createdAt,
    boolean deleted) {}
