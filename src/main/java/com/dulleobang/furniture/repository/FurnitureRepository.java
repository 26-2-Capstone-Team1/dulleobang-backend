package com.dulleobang.furniture.repository;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.furniture.domain.FurnitureItem;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class FurnitureRepository {
  private final JdbcTemplate jdbc;

  public FurnitureRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<FurnitureItem> MAPPER =
      (rs, n) ->
          new FurnitureItem(
              rs.getObject("furniture_id", UUID.class),
              rs.getObject("owner_id", UUID.class),
              rs.getString("name"),
              rs.getString("category"),
              rs.getString("measured_dimensions") == null
                  ? null
                  : StrictJson.parse(rs.getString("measured_dimensions")),
              rs.getString("status"),
              rs.getLong("resource_version"),
              rs.getString("model") == null ? null : StrictJson.parse(rs.getString("model")),
              rs.getObject("active_job_id", UUID.class),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant(),
              rs.getTimestamp("deleted_at") == null
                  ? null
                  : rs.getTimestamp("deleted_at").toInstant());
  private static final String SELECT =
      "SELECT f.*,m.model FROM furniture f LEFT JOIN furniture_models m ON"
          + " m.furniture_id=f.furniture_id AND m.model_version=f.current_model_version";

  public Optional<FurnitureItem> find(UUID id, UUID owner, boolean lock) {
    return jdbc
        .query(
            SELECT + " WHERE f.furniture_id=? AND f.owner_id=?" + (lock ? " FOR UPDATE OF f" : ""),
            MAPPER,
            id,
            owner)
        .stream()
        .findFirst();
  }

  public List<FurnitureItem> list(UUID owner, String status) {
    return jdbc.query(
        SELECT
            + " WHERE f.owner_id=? AND f.deleted_at IS NULL AND (?::text IS NULL OR f.status=?)"
            + " ORDER BY f.created_at DESC,f.furniture_id",
        MAPPER,
        owner,
        status,
        status);
  }

  public void create(
      UUID id, UUID owner, String name, String category, JsonNode dimensions, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " furniture(furniture_id,owner_id,name,category,measured_dimensions,created_at,updated_at)"
            + " VALUES(?,?,?,?,?::jsonb,?,?)",
        id,
        owner,
        name,
        category,
        dimensions == null || dimensions.isNull() ? null : StrictJson.stringify(dimensions),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
  }

  public boolean inUse(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM placement_items i JOIN placement_sets s ON"
                + " s.set_id=i.set_id WHERE i.furniture_id=? AND s.deleted_at IS NULL)",
            Boolean.class,
            id));
  }

  public void patch(UUID id, String name, String category, Instant now) {
    jdbc.update(
        "UPDATE furniture SET name=?,category=?,updated_at=?,resource_version=resource_version+1"
            + " WHERE furniture_id=?",
        name,
        category,
        java.sql.Timestamp.from(now),
        id);
  }

  public void delete(UUID id, Instant now) {
    jdbc.update(
        "UPDATE furniture SET deleted_at=? WHERE furniture_id=?", java.sql.Timestamp.from(now), id);
  }

  public Optional<JsonNode> model(UUID id, int version) {
    return jdbc
        .query(
            "SELECT model FROM furniture_models WHERE furniture_id=? AND model_version=?",
            (rs, n) -> StrictJson.parse(rs.getString(1)),
            id,
            version)
        .stream()
        .findFirst();
  }
}
