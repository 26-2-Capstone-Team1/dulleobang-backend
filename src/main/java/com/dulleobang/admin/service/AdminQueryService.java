package com.dulleobang.admin.service;

import com.dulleobang.admin.repository.AdminQueryRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdminQueryService {
  private final AdminQueryRepository repository;
  private final Clock clock;

  public AdminQueryService(AdminQueryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public List<UUID> queue(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
    return repository.dueJobs(clock.instant(), limit);
  }

  public Map<String, List<UUID>> pending() {
    return repository.pending();
  }
}
