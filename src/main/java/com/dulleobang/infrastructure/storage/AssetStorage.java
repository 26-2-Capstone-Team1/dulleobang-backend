package com.dulleobang.infrastructure.storage;

import java.io.*;
import java.nio.file.Path;

public interface AssetStorage {
  interface Staged extends AutoCloseable {
    Path path();

    void close();
  }

  Staged stage(InputStream input, long expected, String sha256);

  void promote(Staged staged, String sha256);

  byte[] read(String sha256, long expected);

  Path path(String sha256);

  boolean exists(String sha256);

  InputStream open(Path path);
}
