package com.dulleobang.infrastructure.storage;

import java.nio.file.*;

public record TemporaryUpload(Path path, long size, String sha256) implements AutoCloseable {
  public void close() {
    try {
      Files.deleteIfExists(path);
    } catch (java.io.IOException ex) {
      throw new com.dulleobang.common.exception.ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }
}
