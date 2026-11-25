package com.dulleobang.auth.domain;

import java.util.UUID;

public record Credential(UUID userId, String passwordHash) {}
