package com.dulleobang.placement.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

public record PlacementRecord(UUID id, UUID listing, UUID owner, long version, ObjectNode value) {}
