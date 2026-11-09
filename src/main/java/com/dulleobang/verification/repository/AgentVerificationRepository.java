package com.dulleobang.verification.repository;

import com.dulleobang.verification.domain.AgentApplication;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class AgentVerificationRepository {
  private final JdbcTemplate jdbc;

  public AgentVerificationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<AgentApplication> mapper =
      (rs, n) ->
          new AgentApplication(
              rs.getObject("application_id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getString("status"),
              rs.getString("name"),
              rs.getString("license_number"),
              rs.getString("office_name"),
              rs.getString("phone"),
              rs.getObject("document_file_id", UUID.class),
              rs.getString("document_name"),
              rs.getTimestamp("requested_at").toInstant(),
              rs.getTimestamp("reviewed_at") == null
                  ? null
                  : rs.getTimestamp("reviewed_at").toInstant(),
              rs.getString("reject_reason"));

  public Optional<AgentApplication> current(UUID user) {
    return jdbc
        .query(
            "SELECT a.* FROM agent_applications a JOIN agent_profiles p ON"
                + " p.current_application_id=a.application_id WHERE p.user_id=?",
            mapper,
            user)
        .stream()
        .findFirst();
  }

  public Optional<AgentApplication> approved(UUID user) {
    return jdbc
        .query(
            "SELECT a.* FROM agent_applications a JOIN agent_profiles p ON"
                + " p.approved_application_id=a.application_id WHERE p.user_id=?",
            mapper,
            user)
        .stream()
        .findFirst();
  }

  public Optional<AgentApplication> lock(UUID id) {
    return jdbc
        .query("SELECT * FROM agent_applications WHERE application_id=? FOR UPDATE", mapper, id)
        .stream()
        .findFirst();
  }

  public void lockUser(UUID user) {
    jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE", user);
  }

  public void add(AgentApplication a) {
    jdbc.update(
        "INSERT INTO"
            + " agent_applications(application_id,user_id,status,name,license_number,office_name,phone,document_file_id,document_name,requested_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        a.id(),
        a.user(),
        a.status(),
        a.name(),
        a.licenseNumber(),
        a.officeName(),
        a.phone(),
        a.document(),
        a.documentName(),
        java.sql.Timestamp.from(a.requestedAt()));
    jdbc.update(
        "INSERT INTO agent_profiles(user_id,current_application_id) VALUES(?,?) ON"
            + " CONFLICT(user_id) DO UPDATE SET"
            + " current_application_id=EXCLUDED.current_application_id",
        a.user(),
        a.id());
  }

  public boolean isCurrent(UUID user, UUID application) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM agent_profiles WHERE user_id=? AND"
                + " current_application_id=?)",
            Boolean.class,
            user,
            application));
  }

  public void review(AgentApplication a, boolean approve, String reason, Instant now) {
    jdbc.update(
        "UPDATE agent_applications SET status=?,reject_reason=?,reviewed_at=? WHERE"
            + " application_id=?",
        approve ? "APPROVED" : "REJECTED",
        approve ? null : reason,
        java.sql.Timestamp.from(now),
        a.id());
    if (approve) {
      jdbc.update(
          "UPDATE agent_profiles SET approved_application_id=? WHERE user_id=?", a.id(), a.user());
      jdbc.update(
          "UPDATE users SET"
              + " roles=ARRAY['TENANT','LISTING_AGENT']::text[],resource_version=resource_version+1"
              + " WHERE user_id=?",
          a.user());
    }
  }
}
