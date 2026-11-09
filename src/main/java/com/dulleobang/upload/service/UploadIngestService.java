package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.dto.*;
import com.dulleobang.upload.mapper.UploadSourceMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadIngestService {
  private final UploadBatchService batches;
  private final UploadSessionService sessions;
  private final StorageObjectService objects;
  private final SignedUrlService signatures;
  private final UploadQueryService query;

  public UploadIngestService(
      UploadBatchService batches,
      UploadSessionService sessions,
      StorageObjectService objects,
      SignedUrlService signatures,
      UploadQueryService query) {
    this.batches = batches;
    this.sessions = sessions;
    this.objects = objects;
    this.signatures = signatures;
    this.query = query;
  }

  @Transactional
  public UploadBatch store(
      UUID actor, String purpose, String type, UUID target, List<UploadSource> sources) {
    var input = UploadSourceMapper.request(purpose, type, target, sources);
    var batch = batches.open(actor, input);
    Map<UUID, UploadSource> byId = new HashMap<>();
    sources.forEach(s -> byId.put(s.clientId(), s));
    for (var file : batch.files()) write(file, byId.get(file.clientId()));
    sessions.complete(actor, batch.session().id());
    return query.batch(actor, batch.session().id());
  }

  private void write(AssetFile file, UploadSource source) {
    try (var input = source.source().getInputStream()) {
      objects.put(
          file.id(),
          signatures.token(file, "PUT"),
          file.contentType(),
          SignedUrlService.checksum(file),
          source.size(),
          input);
    } catch (IOException ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }
}
