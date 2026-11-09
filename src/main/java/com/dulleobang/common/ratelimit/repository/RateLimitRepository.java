package com.dulleobang.common.ratelimit.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RateLimitRepository {
  private final JdbcTemplate jdbc;

  public RateLimitRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int increment(String bucket, long window) {
    return jdbc.queryForObject(
        "INSERT INTO rate_limit_buckets VALUES(?,?,1) ON CONFLICT(bucket,window_start) DO UPDATE"
            + " SET request_count=rate_limit_buckets.request_count+1 RETURNING request_count",
        Integer.class,
        bucket,
        window);
  }
}
