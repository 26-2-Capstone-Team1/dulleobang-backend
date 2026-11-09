package com.dulleobang.upload.dto;

import org.springframework.core.io.InputStreamSource;

public record UploadedFile(
    String fileName, String contentType, long byteSize, String sha256, InputStreamSource source) {}
