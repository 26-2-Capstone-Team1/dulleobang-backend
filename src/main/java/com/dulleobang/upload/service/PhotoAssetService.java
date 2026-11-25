package com.dulleobang.upload.service;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.image.ImageSanitizer;
import com.dulleobang.upload.repository.UploadRepository;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PhotoAssetService {
  private final UploadRepository repository;
  private final ImageSanitizer images;
  private final AssetStorage storage;
  private final Clock clock;

  public PhotoAssetService(
      UploadRepository repository, ImageSanitizer images, AssetStorage storage, Clock clock) {
    this.repository = repository;
    this.images = images;
    this.storage = storage;
    this.clock = clock;
  }

  @Transactional
  public UUID create(AssetFile source) {
    byte[] clean =
        images.sanitize(storage.read(source.sha256(), source.byteSize()), source.contentType());
    String sha = Hashes.sha256(clean);
    var file =
        new AssetFile(
            UUID.randomUUID(),
            source.owner(),
            null,
            "LISTING",
            source.targetId(),
            "PUBLIC_PHOTO",
            UUID.randomUUID(),
            "photo.jpg",
            "image/jpeg",
            clean.length,
            sha,
            "JPEG",
            "PHOTO",
            source.ordinal(),
            true,
            true,
            null,
            clock.instant());
    try (var staged = storage.stage(new ByteArrayInputStream(clean), clean.length, sha)) {
      repository.lockBlob(sha);
      storage.promote(staged, sha);
      repository.add(file);
    }
    return file.id();
  }
}
