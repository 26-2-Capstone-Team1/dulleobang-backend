package com.dulleobang.infrastructure.storage;

import com.dulleobang.common.exception.ApiException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalAssetReclaimer implements AssetReclaimer {
  private final Path objects;
  private final Path staging;

  public LocalAssetReclaimer(@Value("${dulleobang.data-dir:.runtime/data}") String data) {
    Path root = Path.of(data).toAbsolutePath().normalize();
    objects = root.resolve("objects");
    staging = root.resolve("staging");
  }

  public List<String> oldObjects(Instant before) {
    var result = new ArrayList<String>();
    try (var files = Files.newDirectoryStream(objects)) {
      for (var path : files)
        if (path.getFileName().toString().matches("[a-f0-9]{64}") && oldFile(path, before))
          result.add(path.getFileName().toString());
    } catch (java.io.IOException ex) {
      throw unavailable();
    }
    return result;
  }

  public void deleteObject(String sha, Instant before) {
    if (!sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid object hash");
    deleteOld(objects.resolve(sha), before);
  }

  public void cleanStaging(Instant before) {
    try (var paths = Files.newDirectoryStream(staging)) {
      for (var path : paths) {
        String name = path.getFileName().toString();
        if (name.matches("[0-9a-f-]{36}")) deleteOld(path, before);
        else if (name.startsWith("capture-")
            && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                .toInstant()
                .isBefore(before)) {
          try (var children = Files.newDirectoryStream(path)) {
            for (var child : children)
              if (child.getFileName().toString().matches("[0-9a-f-]{36}")) deleteOld(child, before);
          }
          try {
            Files.delete(path);
          } catch (DirectoryNotEmptyException ignored) {
          }
        }
      }
    } catch (java.io.IOException ex) {
      throw unavailable();
    }
  }

  private boolean oldFile(Path path, Instant before) throws java.io.IOException {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(before);
  }

  private void deleteOld(Path path, Instant before) {
    try {
      if (oldFile(path, before)) Files.deleteIfExists(path);
    } catch (NoSuchFileException ignored) {
    } catch (java.io.IOException ex) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(503, "STORAGE_UNAVAILABLE");
  }
}
