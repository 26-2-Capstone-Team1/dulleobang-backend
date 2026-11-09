package com.dulleobang.common.ratelimit.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.ratelimit.*;
import com.dulleobang.common.ratelimit.repository.RateLimitRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
  private final RateLimitRepository repository;
  private final Clock clock;
  private final org.springframework.transaction.support.TransactionTemplate transactions;

  public RateLimitService(
      RateLimitRepository repository,
      Clock clock,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.repository = repository;
    this.clock = clock;
    transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    transactions.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void checkAll(java.util.Map<String, Integer> buckets) {
    long window = clock.instant().getEpochSecond() / 60;
    boolean rejected =
        Boolean.TRUE.equals(
            transactions.execute(
                tx -> {
                  boolean over = false;
                  for (var entry : buckets.entrySet())
                    over |= repository.increment(entry.getKey(), window) > entry.getValue();
                  return over;
                }));
    if (rejected) throw new ApiException(429, "RATE_LIMIT_EXCEEDED");
  }

  public void check(String bucket, int limit) {
    if (limit < 1) throw new IllegalArgumentException();
    checkAll(java.util.Map.of(bucket, limit));
  }
}
