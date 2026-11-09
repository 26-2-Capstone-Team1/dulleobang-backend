package com.dulleobang.capture.domain;

import java.nio.file.Path;
import java.util.UUID;

public record CaptureInputFile(
    UUID clientId,
    String name,
    String contentType,
    String role,
    long size,
    String sha256,
    Path path) {}
