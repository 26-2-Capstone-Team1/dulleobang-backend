package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.domain.*;
import com.dulleobang.upload.mapper.UploadMapper;
import com.dulleobang.upload.repository.UploadRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class UploadQueryService {
  private final UploadRepository repository;
  private final UploadAccessService access;
  private final SignedUrlService signatures;
  private final Clock clock;

  public UploadQueryService(
      UploadRepository repository,
      UploadAccessService access,
      SignedUrlService signatures,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.signatures = signatures;
    this.clock = clock;
  }

  public UploadSession owned(UUID actor, UUID id, boolean lock, boolean live) {
    var session =
        repository.session(id, lock).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!session.owner().equals(actor)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    access.owned(actor, session.targetType(), session.targetId());
    if (live
        && !session.status().equals("CONSUMED")
        && !session.expiresAt().isAfter(clock.instant()))
      throw new ApiException(410, "UPLOAD_SESSION_EXPIRED");
    return session;
  }

  public Map<String, Object> get(UUID actor, UUID id) {
    var session = owned(actor, id, false, false);
    String status =
        !session.status().equals("CONSUMED") && !session.expiresAt().isAfter(clock.instant())
            ? "EXPIRED"
            : session.status();
    return UploadMapper.session(session, repository.files(id), status);
  }

  public com.dulleobang.upload.dto.UploadBatch batch(UUID actor, UUID id) {
    return new com.dulleobang.upload.dto.UploadBatch(
        owned(actor, id, false, false), repository.files(id));
  }

  public Map<String, Object> download(UUID actor, UUID id) {
    var file =
        repository.asset(id, false).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    access.live(file.targetType(), file.targetId());
    if ((!file.owner().equals(actor) && !repository.publicAsset(id))
        || !file.verified()
        || !file.stored()) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return signatures.grant(file, "GET");
  }
}
