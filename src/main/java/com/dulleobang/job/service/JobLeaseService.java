package com.dulleobang.job.service;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.domain.JobTiming;
import com.dulleobang.job.repository.JobRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class JobLeaseService {
  private final JobRepository repository;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public JobLeaseService(JobRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public String claim(JobRecord job, String worker, String requestedWorker) {
    if (!worker.equals(requestedWorker)) throw new ApiException(403, "WORKER_ID_MISMATCH");
    var now = clock.instant();
    if (!Set.of("QUEUED", "RETRY_WAIT").contains(job.status())
        || job.dueAt().isAfter(now)
        || (job.status().equals("QUEUED")
            && !job.createdAt().plus(JobTiming.QUEUE_LIMIT).isAfter(now))
        || job.attempt() >= 3) throw new ApiException(409, "JOB_NOT_CLAIMABLE");
    byte[] bytes = new byte[48];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    repository.claim(job.id(), worker, digest(token), now);
    return token;
  }

  public void require(JobRecord job, String worker, String lease) {
    var now = clock.instant();
    if (!job.status().equals("RUNNING")
        || !Objects.equals(job.worker(), worker)
        || !Objects.equals(job.leaseHash(), digest(lease))
        || job.leaseExpiresAt() == null
        || !job.leaseExpiresAt().isAfter(now)
        || !job.attemptStartedAt().plus(JobTiming.ATTEMPT_LIMIT).isAfter(now))
      throw new ApiException(409, "JOB_LEASE_LOST");
  }

  public java.time.Instant heartbeat(JobRecord job, int progress, String stage) {
    if (progress < job.progress()) throw new ApiException(422, "PROGRESS_REGRESSION");
    var now = clock.instant();
    var renewal = now.plus(JobTiming.WORKER_LEASE);
    var deadline = job.attemptStartedAt().plus(JobTiming.ATTEMPT_LIMIT);
    var expires = renewal.isBefore(deadline) ? renewal : deadline;
    repository.heartbeat(job.id(), progress, stage, expires, now);
    return expires;
  }

  public static String digest(String token) {
    return Hashes.sha256(Objects.requireNonNullElse(token, "").getBytes(StandardCharsets.UTF_8));
  }
}
