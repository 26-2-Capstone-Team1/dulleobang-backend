package com.dulleobang.common.idempotency.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
  public record Entry(String hash, byte[] payload) {}

  private final JdbcTemplate jdbc;

  public IdempotencyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public java.util.Optional<Entry> find(
      String actor, String method, String path, UUID key, Instant now) {
    return jdbc
        .query(
            "SELECT request_hash,payload FROM idempotency_records WHERE actor=? AND method=? AND"
                + " path=? AND request_key=? AND expires_at>?",
            (rs, n) -> new Entry(rs.getString(1), rs.getBytes(2)),
            actor,
            method,
            path,
            key,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public Entry lock(String actor, String method, String path, UUID key, String hash, Instant now) {
    jdbc.update(
        "DELETE FROM idempotency_records WHERE actor=? AND method=? AND path=? AND request_key=?"
            + " AND expires_at<=?",
        actor,
        method,
        path,
        key,
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO idempotency_records(actor,method,path,request_key,request_hash,expires_at)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        actor,
        method,
        path,
        key,
        hash,
        Timestamp.from(now.plusSeconds(86400)));
    return jdbc.queryForObject(
        "SELECT request_hash,payload FROM idempotency_records WHERE actor=? AND method=? AND path=?"
            + " AND request_key=? FOR UPDATE",
        (rs, n) -> new Entry(rs.getString(1), rs.getBytes(2)),
        actor,
        method,
        path,
        key);
  }

  public void save(String actor, String method, String path, UUID key, byte[] payload) {
    jdbc.update(
        "UPDATE idempotency_records SET payload=? WHERE actor=? AND method=? AND path=? AND"
            + " request_key=?",
        payload,
        actor,
        method,
        path,
        key);
  }
}
