package com.dulleobang.verification.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentApplication(
    UUID id,
    UUID user,
    String status,
    String name,
    String licenseNumber,
    String officeName,
    String phone,
    UUID document,
    String documentName,
    Instant requestedAt,
    Instant reviewedAt,
    String rejectReason) {}
