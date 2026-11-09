package com.dulleobang.user.repository;

import com.dulleobang.user.domain.UserAccount;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
  private final JdbcTemplate jdbc;

  public UserAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<UserAccount> MAPPER =
      (rs, n) ->
          new UserAccount(
              rs.getObject("user_id", UUID.class),
              rs.getString("email"),
              rs.getString("display_name"),
              List.of((String[]) rs.getArray("roles").getArray()),
              List.of((String[]) rs.getArray("auth_providers").getArray()),
              rs.getString("phone"),
              rs.getBoolean("is_phone_public"),
              rs.getLong("resource_version"),
              rs.getTimestamp("created_at").toInstant());

  public Optional<UserAccount> find(UUID id) {
    return jdbc.query("SELECT * FROM users WHERE user_id=?", MAPPER, id).stream().findFirst();
  }

  public UserAccount create(UUID id, String email, String name, String provider, Instant now) {
    jdbc.update(
        "INSERT INTO users(user_id,email,display_name,auth_providers,created_at)"
            + " VALUES(?,?,?,ARRAY[?]::text[],?)",
        id,
        email,
        name,
        provider,
        java.sql.Timestamp.from(now));
    return find(id).orElseThrow();
  }

  public boolean patch(UUID id, long version, String name, String phone, boolean isPublic) {
    return jdbc.update(
            "UPDATE users SET"
                + " display_name=?,phone=?,is_phone_public=?,resource_version=resource_version+1"
                + " WHERE user_id=? AND resource_version=?",
            name,
            phone,
            isPublic,
            id,
            version)
        == 1;
  }
}
