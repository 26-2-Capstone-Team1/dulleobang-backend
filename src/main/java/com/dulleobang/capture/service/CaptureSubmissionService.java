package com.dulleobang.capture.service;

import com.dulleobang.capture.domain.CaptureRecord;
import com.dulleobang.capture.repository.CaptureRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.service.JobCommandService;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CaptureSubmissionService {
  private final CaptureRepository captures;
  private final UploadRepository uploads;
  private final UploadSessionService sessions;
  private final UploadAccessService access;
  private final JobCommandService jobs;
  private final IdempotencyService idempotency;
  private final Clock clock;

  public CaptureSubmissionService(
      CaptureRepository captures,
      UploadRepository uploads,
      UploadSessionService sessions,
      UploadAccessService access,
      JobCommandService jobs,
      IdempotencyService idempotency,
      Clock clock) {
    this.captures = captures;
    this.uploads = uploads;
    this.sessions = sessions;
    this.access = access;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public StoredResponse submit(
      UUID actor, String type, UUID target, UUID session, String path, String key) {
    access.owned(actor, type, target);
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(Map.of("uploadSessionId", session)),
        () -> {
          jobs.lockOwner(actor);
          access.owned(actor, type, target);
        },
        () -> accept(actor, type, target, session, null));
  }

  public StoredResponse accept(UUID actor, String type, UUID target, UUID sessionId, UUID archive) {
    jobs.lockOwner(actor);
    var session = sessions.owned(actor, sessionId, true, true);
    String purpose = type.equals("LISTING") ? "ROOM_CAPTURE" : "FURNITURE_CAPTURE";
    if (!session.status().equals("VERIFIED")
        || !session.targetType().equals(type)
        || !session.targetId().equals(target)
        || !session.purpose().equals(purpose))
      throw new ApiException(409, "INVALID_UPLOAD_SESSION");
    UUID capture = UUID.randomUUID();
    captures.create(
        new CaptureRecord(capture, actor, type, target, sessionId, archive, null, clock.instant()));
    var job =
        jobs.create(
            actor,
            type.equals("LISTING") ? "ROOM_RECONSTRUCTION" : "FURNITURE_RECONSTRUCTION",
            type,
            target,
            type.equals("LISTING") ? target : null,
            capture,
            null);
    captures.latest(capture, job.id());
    uploads.status(sessionId, "CONSUMED");
    return new StoredResponse(
        202,
        StrictJson.stringify(
            Map.of(
                "captureId",
                capture,
                "jobId",
                job.id(),
                "status",
                "QUEUED",
                "pollAfterSeconds",
                3)),
        Map.of("Location", "/api/v1/ai-jobs/" + job.id(), "Retry-After", "3"));
  }

  public void reserve(UUID actor, String type, UUID id) {
    jobs.lockOwner(actor);
    jobs.lockTarget(type, id);
  }

  public void lockOwner(UUID actor) {
    jobs.lockOwner(actor);
  }
}
