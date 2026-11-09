package com.dulleobang.sam3d.service;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Sam3dAssetService {
  private final UploadRepository repository;
  private final AssetStorage storage;
  private final Clock clock;

  public Sam3dAssetService(UploadRepository repository, AssetStorage storage, Clock clock) {
    this.repository = repository;
    this.storage = storage;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public AssetFile save(UUID owner, UUID target, String role, byte[] raw) {
    String hash = Hashes.sha256(raw);
    var file =
        new AssetFile(
            UUID.randomUUID(),
            owner,
            null,
            "FURNITURE",
            target,
            "RESULT",
            UUID.randomUUID(),
            role.toLowerCase(Locale.ROOT) + ".glb",
            "model/gltf-binary",
            raw.length,
            hash,
            "GLB_V2",
            role,
            0,
            true,
            true,
            null,
            clock.instant());
    try (var staged = storage.stage(new ByteArrayInputStream(raw), raw.length, hash)) {
      repository.lockBlob(hash);
      storage.promote(staged, hash);
      repository.add(file);
    }
    return file;
  }
}
