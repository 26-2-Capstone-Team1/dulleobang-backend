package com.dulleobang.job.repository;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.domain.JobRecord;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;

final class JobRowMapper implements RowMapper<JobRecord> {
  private static Instant time(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    var t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  public JobRecord mapRow(ResultSet rs, int row) throws SQLException {
    return new JobRecord(
        rs.getObject("job_id", UUID.class),
        rs.getObject("owner_id", UUID.class),
        rs.getString("type"),
        rs.getString("target_type"),
        rs.getObject("target_id", UUID.class),
        rs.getObject("listing_id", UUID.class),
        rs.getObject("capture_id", UUID.class),
        rs.getString("status"),
        rs.getInt("attempt_no"),
        rs.getInt("progress"),
        rs.getString("stage"),
        result(rs),
        error(rs),
        rs.getObject("retry_of", UUID.class),
        time(rs, "created_at"),
        time(rs, "updated_at"),
        time(rs, "finished_at"),
        rs.getString("lease_hash"),
        time(rs, "lease_expires_at"),
        time(rs, "attempt_started_at"),
        rs.getString("worker_id"),
        time(rs, "due_at"));
  }

  private com.fasterxml.jackson.databind.JsonNode result(ResultSet rs) throws SQLException {
    return rs.getString("result_type") == null
        ? null
        : StrictJson.parse(
            StrictJson.stringify(
                Map.of(
                    "resourceType",
                    rs.getString("result_type"),
                    "resourceId",
                    rs.getObject("result_id"),
                    "version",
                    rs.getInt("result_version"))));
  }

  private com.fasterxml.jackson.databind.JsonNode error(ResultSet rs) throws SQLException {
    return rs.getString("error_code") == null
        ? null
        : StrictJson.parse(
            StrictJson.stringify(
                Map.of(
                    "code",
                    rs.getString("error_code"),
                    "message",
                    rs.getString("error_message"),
                    "retryable",
                    rs.getBoolean("retryable"))));
  }
}
