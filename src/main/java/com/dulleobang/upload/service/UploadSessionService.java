package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.domain.*;
import com.dulleobang.upload.repository.UploadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadSessionService {
  private final UploadRepository repository;
  private final UploadQueryService query;
  private final UploadGrantService grants;
  private final IdempotencyService idempotency;
  private final UploadVerifyService validator;
  private final PhotoAssetService photos;
  private final UploadBatchService batches;

  public UploadSessionService(
      UploadRepository repository,
      UploadQueryService query,
      UploadGrantService grants,
      IdempotencyService idempotency,
      UploadVerifyService validator,
      PhotoAssetService photos,
      UploadBatchService batches) {
    this.repository = repository;
    this.query = query;
    this.grants = grants;
    this.idempotency = idempotency;
    this.validator = validator;
    this.photos = photos;
    this.batches = batches;
  }

  public StoredResponse create(UUID actor, String path, String key, JsonNode input) {
    batches.check(actor, input);
    return grants.refresh(
        idempotency.execute(
            actor.toString(),
            "POST",
            path,
            key,
            StrictJson.stringify(input),
            () -> batches.authorize(actor, input),
            () -> accepted(actor, batches.open(actor, input))));
  }

  private StoredResponse accepted(UUID actor, com.dulleobang.upload.dto.UploadBatch batch) {
    UUID id = batch.session().id();
    return new StoredResponse(
        201,
        StrictJson.stringify(Map.of("session", get(actor, id), "uploads", grants.grants(id))),
        Map.of("Location", "/api/v1/upload-sessions/" + id));
  }

  public UploadSession owned(UUID actor, UUID id, boolean lock, boolean live) {
    return query.owned(actor, id, lock, live);
  }

  public Map<String, Object> get(UUID actor, UUID id) {
    return query.get(actor, id);
  }

  public Map<String, Object> renew(UUID actor, UUID id, JsonNode input) {
    return grants.renew(actor, id, input);
  }

  @Transactional
  public Map<String, Object> complete(UUID actor, UUID id) {
    var session = owned(actor, id, true, true);
    if (Set.of("VERIFIED", "CONSUMED").contains(session.status())) return get(actor, id);
    if (!session.status().equals("OPEN")) throw new ApiException(409, "INVALID_STATE");
    var files = repository.files(id);
    validator.verify(files);
    for (var file : files) {
      UUID derivative = session.purpose().equals("LISTING_PHOTO") ? photos.create(file) : null;
      repository.verified(file.id(), derivative);
    }
    repository.status(id, "VERIFIED");
    return get(actor, id);
  }

  public Map<String, Object> download(UUID actor, UUID id) {
    return query.download(actor, id);
  }
}
