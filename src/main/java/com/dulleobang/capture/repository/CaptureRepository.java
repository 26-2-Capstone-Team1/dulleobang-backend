package com.dulleobang.capture.repository;

import com.dulleobang.capture.domain.CaptureRecord;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CaptureRepository {
  private final JdbcTemplate jdbc;

  public CaptureRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<CaptureRecord> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM captures WHERE capture_id=?",
            (rs, n) ->
                new CaptureRecord(
                    rs.getObject("capture_id", UUID.class),
                    rs.getObject("owner_id", UUID.class),
                    rs.getObject("furniture_id") != null ? "FURNITURE" : "LISTING",
                    rs.getObject(
                        rs.getObject("furniture_id") != null ? "furniture_id" : "listing_id",
                        UUID.class),
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("archive_file_id", UUID.class),
                    rs.getObject("latest_job_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()),
            id)
        .stream()
        .findFirst();
  }

  public void create(CaptureRecord c) {
    jdbc.update(
        "INSERT INTO"
            + " captures(capture_id,owner_id,listing_id,furniture_id,session_id,archive_file_id,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        c.id(),
        c.owner(),
        c.targetType().equals("LISTING") ? c.targetId() : null,
        c.targetType().equals("FURNITURE") ? c.targetId() : null,
        c.session(),
        c.archive(),
        java.sql.Timestamp.from(c.createdAt()));
  }

  public void latest(UUID id, UUID job) {
    jdbc.update("UPDATE captures SET latest_job_id=? WHERE capture_id=?", job, id);
  }
}
