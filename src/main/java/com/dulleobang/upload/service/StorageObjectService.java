package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import java.io.InputStream;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StorageObjectService {
  private final UploadRepository repository;
  private final com.dulleobang.job.repository.JobArtifactRepository artifacts;
  private final UploadAccessService access;
  private final SignedUrlService signatures;
  private final AssetStorage storage;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public StorageObjectService(
      UploadRepository repository,
      com.dulleobang.job.repository.JobArtifactRepository artifacts,
      UploadAccessService access,
      SignedUrlService signatures,
      AssetStorage storage,
      Clock clock,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.repository = repository;
    this.artifacts = artifacts;
    this.access = access;
    this.signatures = signatures;
    this.storage = storage;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
  }

  public AssetFile authorize(UUID id, String token, String method, boolean lock) {
    var file =
        repository.asset(id, false).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    signatures.verify(file, token, method);
    access.live(file.targetType(), file.targetId());
    if (method.equals("PUT")) {
      if (file.session() == null) {
        if (lock) artifacts.lockForFile(id);
        file = repository.asset(id, lock).orElseThrow();
        if (file.verified()) throw new ApiException(409, "IMMUTABLE_FILE");
        if (!artifacts.putAllowed(id, clock.instant()))
          throw new ApiException(409, "JOB_LEASE_LOST");
        return file;
      }
      var session =
          repository
              .session(file.session(), lock)
              .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
      if (!session.expiresAt().isAfter(clock.instant()))
        throw new ApiException(410, "UPLOAD_SESSION_EXPIRED");
      file = repository.asset(id, lock).orElseThrow();
      if (file.verified() || !session.status().equals("OPEN"))
        throw new ApiException(409, "IMMUTABLE_FILE");
    } else if (!file.stored() || !file.verified())
      throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return file;
  }

  public AssetFile put(
      UUID id, String token, String type, String checksum, long length, InputStream input) {
    var file = authorize(id, token, "PUT", false);
    if (!file.contentType().equals(type) || !SignedUrlService.checksum(file).equals(checksum))
      throw new ApiException(422, "UPLOAD_HEADERS_MISMATCH");
    if (length >= 0 && length != file.byteSize()) throw new ApiException(422, "BYTE_SIZE_MISMATCH");
    try (var staged = storage.stage(input, file.byteSize(), file.sha256())) {
      return transactions.execute(
          tx -> {
            var current = authorize(id, token, "PUT", true);
            repository.lockBlob(current.sha256());
            storage.promote(staged, current.sha256());
            repository.stored(id);
            return current;
          });
    }
  }

  public java.nio.file.Path download(UUID id, String token) {
    var file = authorize(id, token, "GET", false);
    var path = storage.path(file.sha256());
    if (!storage.exists(file.sha256())) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return path;
  }
}
