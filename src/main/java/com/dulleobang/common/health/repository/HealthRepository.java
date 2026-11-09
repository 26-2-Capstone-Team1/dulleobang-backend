package com.dulleobang.common.health.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HealthRepository {
  private final JdbcTemplate jdbc;

  public HealthRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void check() {
    jdbc.queryForObject("SELECT 1", Integer.class);
  }
}
