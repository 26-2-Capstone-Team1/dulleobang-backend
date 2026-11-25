package com.dulleobang.common.security.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
  private final JdbcTemplate jdbc;

  public SessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean active(String sid, String user, Instant now) {
    try {
      return Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM sessions WHERE id=? AND user_id=? AND NOT revoked AND"
                  + " expires_at>?)",
              Boolean.class,
              UUID.fromString(sid),
              UUID.fromString(user),
              java.sql.Timestamp.from(now)));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
