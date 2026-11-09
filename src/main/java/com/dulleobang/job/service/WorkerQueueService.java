package com.dulleobang.job.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.job.repository.WorkerQueueRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerQueueService {
  private final WorkerQueueRepository queue;
  private final Clock clock;

  public WorkerQueueService(WorkerQueueRepository queue, Clock clock) {
    this.queue = queue;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> poll(int limit) {
    if (limit < 1 || limit > 50) throw new ApiException(400, "INVALID_QUERY_PARAMETER");
    return Map.of("items", queue.due(clock.instant(), limit));
  }
}
