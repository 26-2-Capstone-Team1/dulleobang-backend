package com.dulleobang.job.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.mapper.JobMapper;
import com.dulleobang.job.repository.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobCommandService {
  private final JobRepository repository;
  private final JobAccessService access;
  private final Clock clock;

  public JobCommandService(JobRepository repository, JobAccessService access, Clock clock) {
    this.repository = repository;
    this.access = access;
    this.clock = clock;
  }

  public void lockTarget(String type, UUID id) {
    if (!repository.lockTarget(type, id)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
  }

  public void lockOwner(UUID actor) {
    repository.lockOwner(actor);
  }

  @Transactional
  public JobRecord create(
      UUID actor,
      String type,
      String targetType,
      UUID target,
      UUID listing,
      UUID capture,
      UUID retryOf) {
    repository.lockOwner(actor);
    if (!repository.lockTarget(targetType, target))
      throw new ApiException(404, "RESOURCE_NOT_FOUND");
    if (repository.activeCount(actor) >= 3) throw new ApiException(409, "ACTIVE_JOB_LIMIT");
    if (repository.targetActive(targetType, target))
      throw new ApiException(409, "ACTIVE_JOB_EXISTS");
    var now = clock.instant();
    UUID id = UUID.randomUUID();
    var job =
        new JobRecord(
            id,
            actor,
            type,
            targetType,
            target,
            listing,
            capture,
            "QUEUED",
            0,
            0,
            "QUEUED",
            null,
            null,
            retryOf,
            now,
            now,
            null,
            null,
            null,
            null,
            null,
            now);
    repository.create(job);
    repository.pointer(job, true, false, now);
    return job;
  }

  @Transactional
  public Map<String, Object> cancel(UUID actor, UUID id) {
    var job = access.owned(actor, id, true);
    if (job.status().equals("CANCELED")) return JobMapper.response(job, false);
    if (!job.active()) throw new ApiException(409, "JOB_TERMINAL");
    return finish(job, "CANCELED", null);
  }

  public Map<String, Object> finish(JobRecord job, String status, JsonNode result) {
    repository.terminal(job.id(), status, result, clock.instant());
    var updated = repository.find(job.id(), false).orElseThrow();
    repository.pointer(updated, false, status.equals("COMPLETED"), clock.instant());
    return JobMapper.response(updated, false);
  }

  public Map<String, Object> fail(JobRecord job, String code, String message) {
    boolean
        retryable =
            Set.of("WORKER_TIMEOUT", "DEPENDENCY_UNAVAILABLE", "INTERNAL_ERROR").contains(code),
        retry = retryable && job.attempt() < 3;
    var now = clock.instant();
    repository.fail(
        job.id(),
        code,
        message,
        retryable,
        retry,
        now.plusSeconds(job.attempt() == 1 ? 10 : 30),
        now);
    var updated = repository.find(job.id(), false).orElseThrow();
    if (!retry) repository.pointer(updated, false, false, now);
    return JobMapper.response(updated, false);
  }

  @Transactional
  public void cancelTarget(String type, UUID id) {
    for (var job : repository.targetJobs(type, id))
      if (job.active()) finish(access.get(job.id(), true), "CANCELED", null);
  }
}
