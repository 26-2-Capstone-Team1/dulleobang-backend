package com.dulleobang.listing.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

public record ListingRecord(
    UUID id, UUID owner, String status, long version, boolean deleted, ObjectNode fields) {}
