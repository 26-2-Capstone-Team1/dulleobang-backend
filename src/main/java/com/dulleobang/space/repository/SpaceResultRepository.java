package com.dulleobang.space.repository;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpaceResultRepository {
  private final JdbcTemplate jdbc;

  public SpaceResultRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int modelVersion(UUID id) {
    return jdbc.queryForObject(
        "SELECT COALESCE(current_model_version,0)+1 FROM furniture WHERE furniture_id=?",
        Integer.class,
        id);
  }

  public int spaceVersion(UUID id) {
    return jdbc.queryForObject(
        "SELECT COALESCE(current_space_version,0)+1 FROM listings WHERE listing_id=?",
        Integer.class,
        id);
  }

  public void model(UUID id, int version, JsonNode model, Instant now) {
    jdbc.update(
        "INSERT INTO furniture_models VALUES(?,?,?::jsonb)",
        id,
        version,
        StrictJson.stringify(model));
    jdbc.update(
        "UPDATE furniture SET"
            + " current_model_version=?,resource_version=resource_version+1,updated_at=? WHERE"
            + " furniture_id=?",
        version,
        java.sql.Timestamp.from(now),
        id);
    invalidate(null, id);
  }

  public void space(UUID id, int version, JsonNode bundle, Instant now) {
    jdbc.update(
        "INSERT INTO spaces(listing_id,space_version,bundle,created_at) VALUES(?,?,?::jsonb,?)",
        id,
        version,
        StrictJson.stringify(bundle),
        java.sql.Timestamp.from(now));
    jdbc.update(
        "UPDATE listings SET"
            + " current_space_version=?,resource_version=resource_version+1,updated_at=? WHERE"
            + " listing_id=?",
        version,
        java.sql.Timestamp.from(now),
        id);
    invalidate(id, null);
  }

  public JsonNode source(UUID id) {
    return jdbc.queryForObject(
        "SELECT source_bundle FROM room_states WHERE state_id=?",
        (rs, n) -> StrictJson.parse(rs.getString(1)),
        id);
  }

  public List<String> removed(UUID id) {
    return jdbc.queryForObject(
        "SELECT removed_object_ids FROM room_states WHERE state_id=?",
        (rs, n) -> Arrays.stream((UUID[]) rs.getArray(1).getArray()).map(UUID::toString).toList(),
        id);
  }

  public void removal(UUID id, JsonNode bundle) {
    jdbc.update(
        "UPDATE room_states SET bundle=?::jsonb WHERE state_id=?",
        StrictJson.stringify(bundle),
        id);
  }

  public void invalidate(UUID listing, UUID furniture) {
    jdbc.update(
        "UPDATE placement_sets SET is_stale=TRUE,resource_version=resource_version+1 WHERE"
            + " deleted_at IS NULL AND is_stale=FALSE AND ((?::uuid IS NOT NULL AND listing_id=?)"
            + " OR set_id IN (SELECT set_id FROM placement_items WHERE furniture_id=?))",
        listing,
        listing,
        furniture);
  }
}
