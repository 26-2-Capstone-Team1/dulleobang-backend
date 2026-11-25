package com.dulleobang.auth.repository;

import com.dulleobang.auth.domain.Credential;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CredentialRepository {
  private final JdbcTemplate jdbc;

  public CredentialRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Credential> find(String email) {
    return jdbc
        .query(
            "SELECT user_id,password_hash FROM credentials WHERE email=?",
            (rs, n) -> new Credential(rs.getObject(1, UUID.class), rs.getString(2)),
            email)
        .stream()
        .findFirst();
  }

  public boolean insert(UUID user, String email, String hash) {
    return jdbc.update(
            "INSERT INTO credentials VALUES(?,?,?) ON CONFLICT(email) DO NOTHING",
            user,
            email,
            hash)
        == 1;
  }

  public void rehash(UUID user, String hash) {
    jdbc.update("UPDATE credentials SET password_hash=? WHERE user_id=?", hash, user);
  }
}
