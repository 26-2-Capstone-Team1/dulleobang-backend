package com.dulleobang.upload.repository;

import com.dulleobang.upload.domain.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class UploadRepository {
  private final JdbcTemplate jdbc;

  public UploadRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  static String targetType(java.sql.ResultSet rs) throws java.sql.SQLException {
    return rs.getObject("furniture_id") != null
        ? "FURNITURE"
        : rs.getObject("listing_id") != null
            ? "LISTING"
            : rs.getObject("target_user_id") != null ? "USER" : "ROOM_STATE";
  }

  static UUID targetId(java.sql.ResultSet rs) throws java.sql.SQLException {
    return rs.getObject(
        targetType(rs).equals("FURNITURE")
            ? "furniture_id"
            : targetType(rs).equals("LISTING")
                ? "listing_id"
                : targetType(rs).equals("USER") ? "target_user_id" : "room_state_id",
        UUID.class);
  }

  public static final RowMapper<AssetFile> ASSET =
      (rs, n) ->
          new AssetFile(
              rs.getObject("file_id", UUID.class),
              rs.getObject("owner_id", UUID.class),
              rs.getObject("session_id", UUID.class),
              targetType(rs),
              targetId(rs),
              rs.getString("purpose"),
              rs.getObject("client_file_id", UUID.class),
              rs.getString("file_name"),
              rs.getString("content_type"),
              rs.getLong("byte_size"),
              rs.getString("sha256"),
              rs.getString("format"),
              rs.getString("role"),
              rs.getInt("ordinal"),
              rs.getBoolean("stored"),
              rs.getBoolean("verified"),
              rs.getObject("derivative_id", UUID.class),
              rs.getTimestamp("created_at").toInstant());

  public Optional<UploadSession> session(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM upload_sessions WHERE session_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new UploadSession(
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("owner_id", UUID.class),
                    rs.getString("purpose"),
                    targetType(rs),
                    targetId(rs),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()),
            id)
        .stream()
        .findFirst();
  }

  public Optional<AssetFile> asset(UUID id, boolean lock) {
    return jdbc
        .query("SELECT * FROM assets WHERE file_id=?" + (lock ? " FOR UPDATE" : ""), ASSET, id)
        .stream()
        .findFirst();
  }

  public List<AssetFile> files(UUID session) {
    return jdbc.query("SELECT * FROM assets WHERE session_id=? ORDER BY ordinal", ASSET, session);
  }

  public void create(UploadSession s) {
    jdbc.update(
        "INSERT INTO"
            + " upload_sessions(session_id,owner_id,purpose,furniture_id,listing_id,target_user_id,created_at,expires_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        s.id(),
        s.owner(),
        s.purpose(),
        target(s.targetType(), "FURNITURE", s.targetId()),
        target(s.targetType(), "LISTING", s.targetId()),
        target(s.targetType(), "USER", s.targetId()),
        java.sql.Timestamp.from(s.createdAt()),
        java.sql.Timestamp.from(s.expiresAt()));
  }

  public void add(AssetFile f) {
    jdbc.update(
        "INSERT INTO"
            + " assets(file_id,owner_id,session_id,furniture_id,listing_id,target_user_id,room_state_id,purpose,client_file_id,file_name,content_type,byte_size,sha256,format,role,ordinal,stored,verified,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        f.id(),
        f.owner(),
        f.session(),
        target(f.targetType(), "FURNITURE", f.targetId()),
        target(f.targetType(), "LISTING", f.targetId()),
        target(f.targetType(), "USER", f.targetId()),
        target(f.targetType(), "ROOM_STATE", f.targetId()),
        f.purpose(),
        f.clientId(),
        f.name(),
        f.contentType(),
        f.byteSize(),
        f.sha256(),
        f.format(),
        f.role(),
        f.ordinal(),
        f.stored(),
        f.verified(),
        java.sql.Timestamp.from(f.createdAt()));
  }

  private UUID target(String type, String expected, UUID id) {
    return type.equals(expected) ? id : null;
  }

  public void status(UUID id, String status) {
    jdbc.update("UPDATE upload_sessions SET status=? WHERE session_id=?", status, id);
  }

  public void lockBlob(String sha) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,44119))", sha);
  }

  public void stored(UUID id) {
    jdbc.update("UPDATE assets SET stored=TRUE WHERE file_id=?", id);
  }

  public void verified(UUID id, UUID derivative) {
    jdbc.update("UPDATE assets SET verified=TRUE,derivative_id=? WHERE file_id=?", derivative, id);
  }

  public boolean listingOwned(UUID id, UUID owner, boolean live) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM listings WHERE listing_id=? AND owner_id=? AND deleted_at"
                + " IS NULL AND (?=FALSE OR status<>'ARCHIVED'))",
            Boolean.class,
            id,
            owner,
            live));
  }

  public boolean listingLive(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM listings WHERE listing_id=? AND deleted_at IS NULL)",
            Boolean.class,
            id));
  }

  public boolean furnitureLive(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM furniture WHERE furniture_id=? AND deleted_at IS NULL)",
            Boolean.class,
            id));
  }

  public boolean stateLive(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM room_states r JOIN listings l ON l.listing_id=r.listing_id"
                + " WHERE r.state_id=? AND r.deleted_at IS NULL AND l.deleted_at IS NULL)",
            Boolean.class,
            id));
  }

  public boolean publicAsset(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM listing_photos p JOIN listings l ON"
                + " l.listing_id=p.listing_id WHERE p.file_id=? AND l.status='PUBLISHED' AND"
                + " l.deleted_at IS NULL) OR EXISTS(SELECT 1 FROM spaces s JOIN listings l ON"
                + " l.listing_id=s.listing_id WHERE l.status='PUBLISHED' AND l.deleted_at IS NULL"
                + " AND (?::text IN"
                + " (s.bundle->'sceneAsset'->>'fileId',s.bundle->'geometryAsset'->>'fileId',s.bundle->'collisionAsset'->>'fileId',s.bundle->'meshSource'->'roomAsset'->>'fileId')"
                + " OR EXISTS(SELECT 1 FROM"
                + " jsonb_array_elements(COALESCE(s.bundle->'meshSource'->'objectAssets','[]'::jsonb))"
                + " m WHERE m->'asset'->>'fileId'=?::text)))",
            Boolean.class,
            id,
            id.toString(),
            id.toString()));
  }
}
