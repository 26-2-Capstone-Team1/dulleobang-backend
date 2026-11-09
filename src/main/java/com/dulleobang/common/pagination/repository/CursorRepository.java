package com.dulleobang.common.pagination.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CursorRepository {
  public record Snapshot(UUID owner, String binding, String ids, Instant expires) {}

  private final JdbcTemplate jdbc;

  public CursorRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void save(UUID id, UUID owner, String binding, String ids, Instant expires) {
    jdbc.update(
        "INSERT INTO cursor_snapshots VALUES(?,?,?,?::jsonb,?)",
        id,
        owner,
        binding,
        ids,
        java.sql.Timestamp.from(expires));
  }

  public Snapshot get(UUID id) {
    return jdbc
        .query(
            "SELECT owner_id,binding,resource_ids,expires_at FROM cursor_snapshots WHERE id=?",
            (rs, n) ->
                new Snapshot(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getTimestamp(4).toInstant()),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }
}
