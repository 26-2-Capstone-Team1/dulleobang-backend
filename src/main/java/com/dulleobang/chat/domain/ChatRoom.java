package com.dulleobang.chat.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

public record ChatRoom(
    UUID id,
    UUID listing,
    UUID inquirer,
    UUID owner,
    ObjectNode snapshot,
    Instant createdAt,
    Instant updatedAt) {}
