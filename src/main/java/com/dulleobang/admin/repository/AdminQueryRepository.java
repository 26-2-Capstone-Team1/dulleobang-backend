package com.dulleobang.admin.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminQueryRepository {
  private final JdbcTemplate jdbc;

  public AdminQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> dueJobs(Instant now, int limit) {
    return jdbc.query(
        "SELECT q.job_id FROM job_queue q JOIN ai_jobs j USING(job_id) WHERE q.available_at<=? AND"
            + " j.status IN ('QUEUED','RETRY_WAIT') ORDER BY q.available_at,q.job_id LIMIT ?",
        (rs, n) -> rs.getObject(1, UUID.class),
        java.sql.Timestamp.from(now),
        limit);
  }

  public Map<String, List<UUID>> pending() {
    return Map.of(
        "applications",
        jdbc.query(
            "SELECT application_id FROM agent_applications WHERE status='PENDING' ORDER BY"
                + " requested_at,application_id",
            (rs, n) -> rs.getObject(1, UUID.class)),
        "reports",
        jdbc.query(
            "SELECT report_id FROM reports WHERE status='OPEN' ORDER BY created_at,report_id",
            (rs, n) -> rs.getObject(1, UUID.class)));
  }
}
