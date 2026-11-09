package com.dulleobang.job.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.repository.JobRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobAccessService {
  private final JobRepository repository;

  public JobAccessService(JobRepository repository) {
    this.repository = repository;
  }

  public JobRecord get(UUID id, boolean lock) {
    var initial =
        repository.find(id, false).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (lock) {
      if (initial.listingId() != null
          && !initial.targetType().equals("LISTING")
          && !repository.lockTarget("LISTING", initial.listingId())) throw missing();
      if (!repository.lockTarget(initial.targetType(), initial.targetId())) throw missing();
      return repository.find(id, true).orElseThrow(this::missing);
    }
    if ((initial.listingId() != null && !repository.targetLive("LISTING", initial.listingId()))
        || !repository.targetLive(initial.targetType(), initial.targetId())) throw missing();
    return initial;
  }

  public JobRecord owned(UUID actor, UUID id, boolean lock) {
    var job = get(id, lock);
    if (!job.owner().equals(actor)) throw missing();
    return job;
  }

  private ApiException missing() {
    return new ApiException(404, "RESOURCE_NOT_FOUND");
  }
}
