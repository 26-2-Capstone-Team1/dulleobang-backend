package com.dulleobang.report.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
  private final JdbcTemplate jdbc;

  public ReportRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(UUID actor, UUID listing) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "report:" + actor + ":" + listing);
  }

  public Optional<UUID> recent(UUID actor, UUID listing, Instant since) {
    return jdbc
        .query(
            "SELECT report_id FROM reports WHERE reporter_id=? AND listing_id=? AND created_at>?"
                + " ORDER BY created_at DESC LIMIT 1",
            (rs, n) -> rs.getObject(1, UUID.class),
            actor,
            listing,
            java.sql.Timestamp.from(since))
        .stream()
        .findFirst();
  }

  public void create(UUID id, UUID actor, UUID listing, String reason, String detail, Instant now) {
    jdbc.update(
        "INSERT INTO reports(report_id,reporter_id,listing_id,reason,detail,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        actor,
        listing,
        reason,
        detail,
        java.sql.Timestamp.from(now));
  }

  public boolean review(UUID id, String status, String resolution, Instant now) {
    return jdbc.update(
            "UPDATE reports SET status=?,resolution=?,reviewed_at=? WHERE report_id=? AND"
                + " status='OPEN'",
            status,
            resolution,
            java.sql.Timestamp.from(now),
            id)
        == 1;
  }
}
