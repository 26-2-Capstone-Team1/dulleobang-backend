package com.dulleobang.space.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

public record SpaceRecord(
    UUID listingId, int spaceVersion, int objectsVersion, ObjectNode bundle, Instant createdAt) {}
