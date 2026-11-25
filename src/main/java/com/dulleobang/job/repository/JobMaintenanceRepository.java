package com.dulleobang.job.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobMaintenanceRepository {
  private final JdbcTemplate jdbc;

  public JobMaintenanceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void publish(Instant now) {
    var events =
        jdbc.queryForList(
            "SELECT * FROM job_outbox WHERE delivered_at IS NULL AND available_at<=? ORDER BY"
                + " available_at FOR UPDATE SKIP LOCKED LIMIT 100",
            java.sql.Timestamp.from(now));
    for (var event : events) {
      jdbc.update(
          "INSERT INTO job_queue SELECT job_id,? FROM ai_jobs WHERE job_id=? AND status IN"
              + " ('QUEUED','RETRY_WAIT') ON CONFLICT(job_id) DO UPDATE SET"
              + " available_at=EXCLUDED.available_at",
          event.get("available_at"),
          event.get("job_id"));
      jdbc.update(
          "UPDATE job_outbox SET delivered_at=? WHERE event_id=?",
          java.sql.Timestamp.from(now),
          event.get("event_id"));
    }
  }

  public void commonCleanup(Instant now) {
    var time = java.sql.Timestamp.from(now);
    jdbc.update("DELETE FROM idempotency_records WHERE expires_at<=?", time);
    jdbc.update("DELETE FROM cursor_snapshots WHERE expires_at<=?", time);
    jdbc.update(
        "DELETE FROM rate_limit_buckets WHERE window_start<?", now.getEpochSecond() / 60 - 2);
    jdbc.update(
        "DELETE FROM refresh_tokens WHERE expires_at<=? AND session_id IN (SELECT id FROM sessions"
            + " WHERE revoked OR expires_at<=?)",
        time,
        time);
    jdbc.update(
        "DELETE FROM sessions WHERE expires_at<=? AND NOT EXISTS(SELECT 1 FROM refresh_tokens WHERE"
            + " session_id=sessions.id)",
        time);
  }

  public List<UUID> queue(Instant now) {
    return jdbc.query(
        "SELECT job_id FROM job_queue WHERE available_at<=? ORDER BY available_at,job_id",
        (rs, n) -> rs.getObject(1, UUID.class),
        java.sql.Timestamp.from(now));
  }
}
