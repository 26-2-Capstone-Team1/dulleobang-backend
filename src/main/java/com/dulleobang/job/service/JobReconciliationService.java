package com.dulleobang.job.service;

import com.dulleobang.job.repository.JobRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobReconciliationService {
  private final JobRepository jobs;
  private final JobAccessService access;
  private final JobCommandService commands;
  private final Clock clock;

  public JobReconciliationService(
      JobRepository jobs, JobAccessService access, JobCommandService commands, Clock clock) {
    this.jobs = jobs;
    this.access = access;
    this.commands = commands;
    this.clock = clock;
  }

  @Transactional
  public void reconcile(UUID id) {
    var now = clock.instant();

    com.dulleobang.job.domain.JobRecord job;
    try {
      job = access.get(id, true);
    } catch (com.dulleobang.common.exception.ApiException ex) {
      if (ex.status() != 404) throw ex;
      jobs.find(id, true)
          .filter(com.dulleobang.job.domain.JobRecord::active)
          .ifPresent(orphan -> commands.finish(orphan, "CANCELED", null));
      return;
    }
    if (job.status().equals("RUNNING")
        && (!job.leaseExpiresAt().isAfter(now)
            || !job.attemptStartedAt().plusSeconds(1800).isAfter(now)))
      commands.fail(job, "WORKER_TIMEOUT", "AI worker lease expired.");
    else if (job.status().equals("QUEUED") && !job.createdAt().plusSeconds(3600).isAfter(now)) {
      jobs.fail(
          job.id(), "INTERNAL_ERROR", "AI queue wait exceeded 60 minutes.", true, false, now, now);
      jobs.pointer(jobs.find(job.id(), false).orElseThrow(), false, false, now);
    }
  }
}
