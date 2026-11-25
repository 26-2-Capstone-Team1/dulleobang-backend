package com.dulleobang.job.domain;

import java.time.Duration;

public final class JobTiming {
  public static final Duration ATTEMPT_LIMIT = Duration.ofHours(2);
  public static final Duration QUEUE_LIMIT = Duration.ofHours(3);
  public static final Duration WORKER_LEASE = Duration.ofMinutes(2);

  private JobTiming() {}
}
