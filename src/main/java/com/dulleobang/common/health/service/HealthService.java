package com.dulleobang.common.health.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.health.repository.HealthRepository;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
  private final HealthRepository repository;

  public HealthService(HealthRepository repository) {
    this.repository = repository;
  }

  public void check() {
    try {
      repository.check();
    } catch (Exception ex) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    }
  }
}
