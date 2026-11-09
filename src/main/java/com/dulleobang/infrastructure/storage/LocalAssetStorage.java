package com.dulleobang.infrastructure.storage;

import com.dulleobang.common.crypto.Hashes;
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
public class LocalAssetStorage implements AssetStorage {
  private final Path objects;
  private final Path staging;

  public LocalAssetStorage(@Value("${dulleobang.data-dir:.runtime/data}") String root)
      throws IOException {
    Path base = Path.of(root).toAbsolutePath().normalize();
    objects = base.resolve("objects");
    staging = base.resolve("staging");
    Files.createDirectories(objects);
    Files.createDirectories(staging);
  }

  public Staged stage(InputStream input, long expected, String sha256) {
    Path temporary = staging.resolve(UUID.randomUUID().toString());
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      long size = 0;
      try (var channel =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        byte[] chunk = new byte[65536];
        int count;
        while ((count = input.read(chunk)) != -1) {
          size += count;
          if (size > expected) throw new ApiException(413, "UPLOAD_TOO_LARGE");
          hash.update(chunk, 0, count);
          var bytes = ByteBuffer.wrap(chunk, 0, count);
          while (bytes.hasRemaining()) channel.write(bytes);
        }
        channel.force(true);
      }
      if (size != expected || !HexFormat.of().formatHex(hash.digest()).equals(sha256))
        throw new ApiException(422, "CHECKSUM_MISMATCH");
      return new LocalStage(temporary);
    } catch (ApiException ex) {
      cleanup(temporary);
      throw ex;
    } catch (Exception ex) {
      cleanup(temporary);
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }

  public void promote(Staged staged, String sha256) {
    try {
      Files.move(
          staged.path(),
          path(sha256),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ex) {
      try {
        Files.move(staged.path(), path(sha256), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException failure) {
        throw new ApiException(503, "STORAGE_UNAVAILABLE");
      }
    } catch (IOException ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }

  public byte[] read(String sha256, long expected) {
    try {
      Path path = path(sha256);
      if (!Files.isRegularFile(path)) throw new ApiException(409, "UPLOAD_INCOMPLETE");
      if (Files.size(path) != expected || expected > 256L * 1024 * 1024)
        throw new ApiException(422, "BYTE_SIZE_MISMATCH");
      byte[] bytes = Files.readAllBytes(path);
      if (!Hashes.sha256(bytes).equals(sha256)) throw new ApiException(422, "CHECKSUM_MISMATCH");
      return bytes;
    } catch (IOException ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }

  public Path path(String sha256) {
    if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid content hash");
    return objects.resolve(sha256);
  }

  public boolean exists(String sha256) {
    return Files.isRegularFile(path(sha256));
  }

  public InputStream open(Path path) {
    try {
      return Files.newInputStream(path);
    } catch (IOException ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }

  private static void cleanup(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }

  private record LocalStage(Path path) implements Staged {
    public void close() {
      cleanup(path);
    }
  }
}
