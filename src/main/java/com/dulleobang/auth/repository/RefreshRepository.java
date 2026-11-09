package com.dulleobang.auth.repository;

import com.dulleobang.auth.domain.RefreshSession;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshRepository {
  private final JdbcTemplate jdbc;

  public RefreshRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void create(UUID session, UUID user, Instant until) {
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,?,FALSE)", session, user, java.sql.Timestamp.from(until));
  }

  public void add(String digest, UUID session, Instant until) {
    jdbc.update(
        "INSERT INTO refresh_tokens VALUES(?,?,?,FALSE)",
        digest,
        session,
        java.sql.Timestamp.from(until));
  }

  public Optional<UUID> session(String digest) {
    return jdbc
        .query(
            "SELECT session_id FROM refresh_tokens WHERE digest=?",
            (rs, n) -> rs.getObject(1, UUID.class),
            digest)
        .stream()
        .findFirst();
  }

  public Optional<RefreshSession> lock(UUID session) {
    return jdbc
        .query(
            "SELECT * FROM sessions WHERE id=? FOR UPDATE",
            (rs, n) ->
                new RefreshSession(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getBoolean("revoked")),
            session)
        .stream()
        .findFirst();
  }

  public boolean valid(String digest, UUID session, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM refresh_tokens WHERE digest=? AND session_id=? AND"
                + " expires_at>?)",
            Boolean.class,
            digest,
            session,
            java.sql.Timestamp.from(now)));
  }

  public boolean consume(String digest) {
    return jdbc.update(
            "UPDATE refresh_tokens SET consumed=TRUE WHERE digest=? AND consumed=FALSE", digest)
        == 1;
  }

  public void extend(UUID session, Instant until) {
    jdbc.update(
        "UPDATE sessions SET expires_at=? WHERE id=?", java.sql.Timestamp.from(until), session);
  }

  public void revoke(UUID session) {
    jdbc.update("UPDATE sessions SET revoked=TRUE WHERE id=?", session);
  }
}
