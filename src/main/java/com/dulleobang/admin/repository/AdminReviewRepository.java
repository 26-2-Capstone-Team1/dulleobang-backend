package com.dulleobang.admin.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminReviewRepository {
  private final JdbcTemplate jdbc;

  public AdminReviewRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void add(
      String kind, UUID id, String reviewer, String decision, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO admin_reviews VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        kind,
        id,
        reviewer,
        decision,
        reason,
        java.sql.Timestamp.from(now));
  }
}
