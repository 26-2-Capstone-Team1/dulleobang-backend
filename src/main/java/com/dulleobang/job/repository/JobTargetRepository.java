package com.dulleobang.job.repository;

import com.dulleobang.job.domain.JobRecord;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobTargetRepository {
  private final JdbcTemplate jdbc;

  public JobTargetRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static java.sql.Timestamp ts(Instant value) {
    return value == null ? null : java.sql.Timestamp.from(value);
  }

  public void lockOwner(UUID owner) {
    jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR NO KEY UPDATE", owner);
  }

  public boolean targetLive(String type, UUID id) {
    String table =
        type.equals("LISTING")
            ? "listings"
            : type.equals("FURNITURE") ? "furniture" : "room_states";
    String column =
        type.equals("LISTING")
            ? "listing_id"
            : type.equals("FURNITURE") ? "furniture_id" : "state_id";
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM "
                + table
                + " WHERE "
                + column
                + "=? AND deleted_at IS NULL)",
            Boolean.class,
            id));
  }

  public boolean lockTarget(String type, UUID id) {
    String
        table =
            type.equals("LISTING")
                ? "listings"
                : type.equals("FURNITURE") ? "furniture" : "room_states",
        column =
            type.equals("LISTING")
                ? "listing_id"
                : type.equals("FURNITURE") ? "furniture_id" : "state_id";
    return !jdbc.queryForList(
            "SELECT "
                + column
                + " FROM "
                + table
                + " WHERE "
                + column
                + "=? AND deleted_at IS NULL FOR UPDATE",
            id)
        .isEmpty();
  }

  public void pointer(JobRecord j, boolean active, boolean success, Instant now) {
    if (j.targetType().equals("ROOM_STATE")) {
      jdbc.update(
          "UPDATE room_states SET status=?,job_id=? WHERE state_id=?",
          active
              ? "PROCESSING"
              : success ? "READY" : j.status().equals("FAILED") ? "FAILED" : "CANCELED",
          j.id(),
          j.targetId());
      return;
    }
    String table = j.targetType().equals("LISTING") ? "listings" : "furniture",
        id = j.targetType().equals("LISTING") ? "listing_id" : "furniture_id";
    String status =
        j.targetType().equals("FURNITURE")
            ? ",status="
                + (active
                    ? "'PROCESSING'"
                    : "CASE WHEN current_model_version IS NOT NULL THEN 'READY' WHEN '"
                        + j.status()
                        + "'='FAILED' THEN 'FAILED' ELSE 'DRAFT' END")
            : "";
    jdbc.update(
        "UPDATE "
            + table
            + " SET active_job_id=?,resource_version=resource_version+1,updated_at=?"
            + status
            + " WHERE "
            + id
            + "=?"
            + (active ? "" : " AND active_job_id=?"),
        active
            ? new Object[] {j.id(), ts(now), j.targetId()}
            : new Object[] {null, ts(now), j.targetId(), j.id()});
  }
}
