package com.dulleobang.infrastructure.storage;

import com.dulleobang.common.exception.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class CatalogSourceFiles {
  private final AssetStorage storage;

  public CatalogSourceFiles(AssetStorage storage) {
    this.storage = storage;
  }

  public byte[] metadata(Path directory) throws IOException {
    var path = directory.resolve("meta.json");
    if (Files.size(path) > 1024 * 1024) throw new IllegalArgumentException("meta.json too large");
    return Files.readAllBytes(path);
  }

  public StagedAsset stage(Path directory, String filename, long maxBytes, String sha256)
      throws IOException {
    var path = directory.resolve(filename);
    long size = Files.size(path);
    if (size <= 0 || size > maxBytes)
      throw new IllegalArgumentException("Invalid size for " + filename);
    try (var in = Files.newInputStream(path)) {
      return new StagedAsset(filename, size, sha256, storage.stage(in, size, sha256));
    } catch (ApiException ex) {
      throw new IllegalArgumentException(filename + ": " + ex.code(), ex);
    }
  }

  public record StagedAsset(String filename, long size, String sha256, AssetStorage.Staged staged)
      implements AutoCloseable {
    public byte[] bytes() throws IOException {
      return Files.readAllBytes(staged.path());
    }

    @Override
    public void close() {
      staged.close();
    }
  }
}
