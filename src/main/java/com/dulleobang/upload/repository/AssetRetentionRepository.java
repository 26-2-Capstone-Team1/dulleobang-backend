package com.dulleobang.upload.repository;

import com.dulleobang.upload.domain.AssetFile;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRetentionRepository {
  private static final String ELIGIBLE =
      """
      (EXISTS(SELECT 1 FROM listings l WHERE l.listing_id=a.listing_id AND l.deleted_at IS NOT NULL)
      OR EXISTS(SELECT 1 FROM furniture f WHERE f.furniture_id=a.furniture_id AND f.deleted_at IS NOT NULL)
      OR EXISTS(SELECT 1 FROM room_states r JOIN listings l USING(listing_id) WHERE r.state_id=a.room_state_id AND (r.deleted_at IS NOT NULL OR l.deleted_at IS NOT NULL))
      OR (a.session_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM agent_applications v WHERE v.document_file_id=a.file_id) AND EXISTS(
      SELECT 1 FROM upload_sessions s WHERE s.session_id=a.session_id AND ((s.status<>'CONSUMED' AND s.expires_at+INTERVAL '1 day'<=CAST(:now AS timestamptz)) OR (s.status='CONSUMED' AND EXISTS(
      SELECT 1 FROM captures c JOIN ai_jobs j ON j.job_id=c.latest_job_id WHERE c.session_id=s.session_id AND j.status NOT IN ('QUEUED','RUNNING','RETRY_WAIT') AND j.updated_at+INTERVAL '30 days'<=CAST(:now AS timestamptz))))))
      OR (a.job_id IS NOT NULL AND a.verified=FALSE AND a.created_at+INTERVAL '1 day'<=CAST(:now AS timestamptz) AND NOT EXISTS(SELECT 1 FROM ai_jobs j WHERE j.job_id=a.job_id AND j.status='RUNNING' AND j.attempt_no=a.attempt_no))
      OR (a.purpose='CAPTURE_ARCHIVE' AND EXISTS(SELECT 1 FROM captures c JOIN ai_jobs j ON j.job_id=c.latest_job_id WHERE c.archive_file_id=a.file_id AND j.status NOT IN ('QUEUED','RUNNING','RETRY_WAIT') AND j.updated_at+INTERVAL '30 days'<=CAST(:now AS timestamptz))))
      """;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public AssetRetentionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  public List<UUID> candidates(Instant now) {
    return named.query(
        "SELECT a.file_id FROM assets a WHERE a.stored AND "
            + ELIGIBLE
            + " ORDER BY a.created_at,a.file_id LIMIT 1000",
        Map.of("now", java.sql.Timestamp.from(now)),
        (rs, n) -> rs.getObject(1, UUID.class));
  }

  public void lockParent(AssetFile file) {
    switch (file.targetType()) {
      case "LISTING" ->
          jdbc.queryForList(
              "SELECT listing_id FROM listings WHERE listing_id=? FOR UPDATE", file.targetId());
      case "FURNITURE" ->
          jdbc.queryForList(
              "SELECT furniture_id FROM furniture WHERE furniture_id=? FOR UPDATE",
              file.targetId());
      case "ROOM_STATE" -> {
        jdbc.queryForList(
            "SELECT l.listing_id FROM listings l JOIN room_states r USING(listing_id) WHERE"
                + " r.state_id=? FOR UPDATE OF l",
            file.targetId());
        jdbc.queryForList(
            "SELECT state_id FROM room_states WHERE state_id=? FOR UPDATE", file.targetId());
      }
      default -> {}
    }
  }

  public void lockBlob(String sha) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,44119))", sha);
  }

  public void retire(UUID id, Instant now) {
    named.update(
        "UPDATE assets a SET stored=FALSE WHERE a.file_id=:id AND a.stored AND " + ELIGIBLE,
        Map.of("id", id, "now", java.sql.Timestamp.from(now)));
  }

  public boolean retained(String sha) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM assets WHERE sha256=? AND stored)", Boolean.class, sha));
  }

  public void expireSessions(Instant now) {
    jdbc.update(
        "UPDATE upload_sessions SET status='EXPIRED' WHERE status IN ('OPEN','VERIFIED') AND"
            + " expires_at<=?",
        java.sql.Timestamp.from(now));
  }
}
