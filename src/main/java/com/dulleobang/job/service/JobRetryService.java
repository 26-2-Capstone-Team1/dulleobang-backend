package com.dulleobang.job.service;

import com.dulleobang.capture.repository.CaptureRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.repository.JobRepository;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.UploadAccessService;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class JobRetryService {
  private final JobAccessService access;
  private final JobCommandService commands;
  private final JobRepository jobs;
  private final CaptureRepository captures;
  private final UploadRepository uploads;
  private final UploadAccessService targets;
  private final IdempotencyService idempotency;
  private final Clock clock;

  public JobRetryService(
      JobAccessService access,
      JobCommandService commands,
      JobRepository jobs,
      CaptureRepository captures,
      UploadRepository uploads,
      UploadAccessService targets,
      IdempotencyService idempotency,
      Clock clock) {
    this.access = access;
    this.commands = commands;
    this.jobs = jobs;
    this.captures = captures;
    this.uploads = uploads;
    this.targets = targets;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public StoredResponse retry(UUID actor, UUID id, String path, String key) {
    access.owned(actor, id, false);
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        "null",
        () -> access.owned(actor, id, false),
        () -> {
          commands.lockOwner(actor);
          var old = access.owned(actor, id, true);
          if (!old.status().equals("FAILED")
              || old.error() == null
              || !old.error().path("retryable").asBoolean())
            throw new ApiException(409, "JOB_NOT_RETRYABLE");
          if (old.targetType().equals("LISTING")) targets.owned(actor, "LISTING", old.targetId());
          if (old.captureId() != null) {
            var capture = captures.find(old.captureId()).orElseThrow();
            var latest = jobs.find(capture.latestJob(), false).orElseThrow();
            if ((!latest.active()
                    && !latest.updatedAt().plusSeconds(30L * 86400).isAfter(clock.instant()))
                || uploads.files(capture.session()).stream().anyMatch(f -> !f.stored()))
              throw new ApiException(410, "INPUT_EXPIRED");
          }
          var created =
              commands.create(
                  actor,
                  old.type(),
                  old.targetType(),
                  old.targetId(),
                  old.listingId(),
                  old.captureId(),
                  id);
          if (old.captureId() != null) captures.latest(old.captureId(), created.id());
          return new StoredResponse(
              202,
              StrictJson.stringify(
                  Map.of("jobId", created.id(), "status", "QUEUED", "pollAfterSeconds", 3)),
              Map.of("Location", "/api/v1/ai-jobs/" + created.id(), "Retry-After", "3"));
        });
  }
}
