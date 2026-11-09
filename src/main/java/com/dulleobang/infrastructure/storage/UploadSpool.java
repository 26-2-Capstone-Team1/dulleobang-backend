package com.dulleobang.infrastructure.storage;

import com.dulleobang.common.exception.ApiException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UploadSpool {
  private final Path staging;

  public UploadSpool(@Value("${dulleobang.data-dir:.runtime/data}") String data)
      throws IOException {
    staging = Path.of(data).toAbsolutePath().normalize().resolve("staging");
    Files.createDirectories(staging);
  }

  public TemporaryUpload copy(InputStream input, long maximum) {
    Path path = staging.resolve(UUID.randomUUID().toString());
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      long size = 0;
      try (var output =
          FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          size += count;
          if (size > maximum) throw new ApiException(413, "CAPTURE_ZIP_TOO_LARGE");
          digest.update(buffer, 0, count);
          var b = ByteBuffer.wrap(buffer, 0, count);
          while (b.hasRemaining()) output.write(b);
        }
        output.force(true);
      }
      return new TemporaryUpload(path, size, HexFormat.of().formatHex(digest.digest()));
    } catch (Exception ex) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
      }
      if (ex instanceof ApiException api) throw api;
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }

  public Path newDirectory() {
    try {
      return Files.createTempDirectory(staging, "capture-");
    } catch (IOException ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }
}
