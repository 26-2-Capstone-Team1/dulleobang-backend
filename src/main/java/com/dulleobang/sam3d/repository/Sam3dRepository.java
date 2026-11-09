package com.dulleobang.sam3d.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Sam3dRepository {
  public record Import(UUID furniture, String hash) {}

  private final JdbcTemplate jdbc;

  public Sam3dRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(UUID owner, String job) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,79213))", owner + ":" + job);
  }

  public Optional<Import> find(UUID owner, String job) {
    return jdbc
        .query(
            "SELECT i.furniture_id,i.request_hash FROM sam3d_imports i JOIN furniture f"
                + " USING(furniture_id) WHERE i.owner_id=? AND i.studio_job_id=? AND f.deleted_at"
                + " IS NULL ORDER BY i.created_at DESC LIMIT 1",
            (rs, n) -> new Import(rs.getObject(1, UUID.class), rs.getString(2)),
            owner,
            job)
        .stream()
        .findFirst();
  }

  public void save(UUID furniture, UUID owner, String job, String hash, Instant now) {
    jdbc.update(
        "INSERT INTO sam3d_imports VALUES(?,?,?,?,'BOUNDING_BOX',?)",
        furniture,
        owner,
        job,
        hash,
        java.sql.Timestamp.from(now));
    jdbc.update("UPDATE furniture SET status='READY' WHERE furniture_id=?", furniture);
  }
}
