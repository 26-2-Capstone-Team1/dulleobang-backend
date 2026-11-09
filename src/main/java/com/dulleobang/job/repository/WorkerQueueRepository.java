package com.dulleobang.job.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkerQueueRepository {
  private final JdbcTemplate jdbc;

  public WorkerQueueRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> due(Instant now, int limit) {
    return jdbc.query(
        "SELECT j.job_id,j.type FROM job_queue q JOIN ai_jobs j USING(job_id)"
            + " WHERE q.available_at<=? AND j.due_at<=?"
            + " AND j.status IN ('QUEUED','RETRY_WAIT') AND j.attempt_no<3"
            + " ORDER BY q.available_at,q.job_id LIMIT ?",
        (rs, n) ->
            Map.<String, Object>of("jobId", rs.getObject(1, UUID.class), "type", rs.getString(2)),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now),
        limit);
  }
}
