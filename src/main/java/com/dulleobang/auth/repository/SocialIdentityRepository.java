package com.dulleobang.auth.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SocialIdentityRepository {
  private final JdbcTemplate jdbc;

  public SocialIdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(String provider, String subject) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", provider + ":" + subject);
  }

  public Optional<UUID> find(String provider, String subject) {
    return jdbc
        .query(
            "SELECT user_id FROM oauth_identities WHERE provider=? AND subject=?",
            (rs, n) -> rs.getObject(1, UUID.class),
            provider,
            subject)
        .stream()
        .findFirst();
  }

  public void insert(String provider, String subject, UUID user) {
    jdbc.update("INSERT INTO oauth_identities VALUES(?,?,?)", provider, subject, user);
  }
}
