package com.dulleobang.infrastructure.storage;

import java.time.Instant;
import java.util.List;

public interface AssetReclaimer {
  List<String> oldObjects(Instant before);

  void deleteObject(String sha, Instant before);

  void cleanStaging(Instant before);
}
