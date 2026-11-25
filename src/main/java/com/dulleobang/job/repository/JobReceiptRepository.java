package com.dulleobang.job.repository;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.domain.JobRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobReceiptRepository {
  private final JdbcTemplate jdbc;

  public JobReceiptRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<JsonNode> replay(
      JobRecord job, String operation, String worker, String lease, String body) {
    return jdbc
        .query(
            "SELECT * FROM job_receipts WHERE job_id=? AND attempt_no=? AND operation=?",
            (rs, n) -> {
              if (!rs.getString("worker_id").equals(worker)
                  || !rs.getString("lease_hash").equals(lease))
                throw new ApiException(409, "JOB_LEASE_LOST");
              if (!rs.getString("body_hash").equals(StrictJson.digest(body)))
                throw new ApiException(409, "CALLBACK_CONFLICT");
              return StrictJson.parse(rs.getString("response"));
            },
            job.id(),
            job.attempt(),
            operation)
        .stream()
        .findFirst();
  }

  public void save(
      JobRecord job,
      String operation,
      String worker,
      String lease,
      String body,
      JsonNode response) {
    jdbc.update(
        "INSERT INTO job_receipts VALUES(?,?,?,?,?,?,?::jsonb)",
        job.id(),
        job.attempt(),
        operation,
        worker,
        lease,
        StrictJson.digest(body),
        StrictJson.stringify(response));
  }
}
