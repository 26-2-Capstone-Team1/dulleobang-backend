package com.dulleobang.user.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDeletionRepository {
  private final JdbcTemplate jdbc;

  public AccountDeletionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void delete(UUID accountId) {
    // Captures and jobs can reference each other while both are being removed.
    jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
    jdbc.update("DELETE FROM users WHERE user_id=?", accountId);
    jdbc.update("DELETE FROM idempotency_records WHERE actor=?", accountId.toString());
  }
}
