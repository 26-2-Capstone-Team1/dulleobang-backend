package com.dulleobang.infrastructure.storage;

import com.dulleobang.capture.domain.CaptureInputFile;
import java.nio.file.*;
import java.util.*;

public record PreparedCapture(Path directory, List<CaptureInputFile> files)
    implements AutoCloseable {
  public void close() {
    try {
      for (var file : files) {
        if (!file.path()
            .toAbsolutePath()
            .normalize()
            .getParent()
            .equals(directory.toAbsolutePath().normalize()))
          throw new IllegalStateException("Temporary capture escaped its directory");
        Files.deleteIfExists(file.path());
      }
      Files.deleteIfExists(directory);
    } catch (java.io.IOException ex) {
      throw new com.dulleobang.common.exception.ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }
}
