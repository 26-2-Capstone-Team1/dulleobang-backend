package com.dulleobang.upload.dto;

import java.util.UUID;
import org.springframework.core.io.InputStreamSource;

public record UploadSource(
    UUID clientId,
    String name,
    String contentType,
    String role,
    long size,
    String sha256,
    InputStreamSource source) {}
