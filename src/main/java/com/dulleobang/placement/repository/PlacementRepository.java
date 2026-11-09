package com.dulleobang.placement.repository;

import com.dulleobang.common.json.*;
import com.dulleobang.placement.domain.PlacementRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlacementRepository {
  private final JdbcTemplate jdbc;

  public PlacementRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PlacementRecord> find(UUID owner, UUID listing, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM placement_sets WHERE owner_id=? AND listing_id=? AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : ""),
            this::map,
            owner,
            listing)
        .stream()
        .findFirst();
  }

  public List<PlacementRecord> visible(UUID owner) {
    return jdbc.query(
        "SELECT p.* FROM placement_sets p JOIN listings l USING(listing_id) WHERE p.owner_id=? AND"
            + " p.deleted_at IS NULL AND l.deleted_at IS NULL AND (l.status='PUBLISHED' OR"
            + " l.owner_id=?) ORDER BY p.saved_at DESC,p.set_id",
        this::map,
        owner,
        owner);
  }

  private PlacementRecord map(ResultSet rs, int row) throws SQLException {
    var value = JsonNodeFactory.instance.objectNode();
    UUID id = rs.getObject("set_id", UUID.class),
        listing = rs.getObject("listing_id", UUID.class),
        owner = rs.getObject("owner_id", UUID.class);
    value
        .put("placementSetId", id.toString())
        .put("listingId", listing.toString())
        .put("ownerId", owner.toString())
        .put("version", rs.getLong("resource_version"));
    var selection = value.putObject("space");
    selection
        .put("kind", rs.getString("space_kind").equals("SPACE") ? "ORIGINAL" : "ROOM_STATE")
        .put("spaceVersion", rs.getInt("space_version"));
    selection.put("roomStateId", rs.getString("room_state_id"));
    var items = value.putArray("placements");
    jdbc.query("SELECT * FROM placement_items WHERE set_id=? ORDER BY ordinal", this::mapItem, id)
        .forEach(items::add);
    value.set(
        "clientCollisionReport",
        rs.getString("collision_report") == null
            ? NullNode.instance
            : StrictJson.parse(rs.getString("collision_report")));
    value
        .put("validationAuthority", "CLIENT_ONLY")
        .put("isStale", rs.getBoolean("is_stale"))
        .put("savedAt", ApiTime.format(rs.getTimestamp("saved_at").toInstant()));
    return new PlacementRecord(id, listing, owner, rs.getLong("resource_version"), value);
  }

  private ObjectNode mapItem(ResultSet rs, int row) throws SQLException {
    var item = JsonNodeFactory.instance.objectNode();
    item.put("placementId", rs.getString("placement_id"))
        .put("furnitureId", rs.getString("furniture_id"))
        .put("modelVersion", rs.getInt("model_version"));
    var transform = item.putObject("transform");
    var position = transform.putObject("position");
    var rotation = transform.putObject("rotation");
    for (String axis : List.of("x", "y", "z")) position.put(axis, rs.getDouble("position_" + axis));
    for (String axis : List.of("x", "y", "z", "w"))
      rotation.put(axis, rs.getDouble("rotation_" + axis));
    return item;
  }

  public void save(UUID id, UUID owner, UUID listing, boolean create, JsonNode input, Instant now) {
    var space = input.path("space");
    String kind = space.path("kind").asText().equals("ORIGINAL") ? "SPACE" : "ROOM_STATE";
    UUID state =
        space.path("roomStateId").isNull()
            ? null
            : UUID.fromString(space.path("roomStateId").asText());
    String report =
        input.path("clientCollisionReport").isNull()
            ? null
            : StrictJson.stringify(input.path("clientCollisionReport"));
    if (create) {
      jdbc.update(
          "DELETE FROM placement_sets WHERE owner_id=? AND listing_id=? AND deleted_at IS NOT NULL",
          owner,
          listing);
      jdbc.update(
          "INSERT INTO"
              + " placement_sets(set_id,owner_id,listing_id,resource_version,space_kind,space_version,room_state_id,collision_report,saved_at)"
              + " VALUES(?,?,?,1,?,?,?,?::jsonb,?)",
          id,
          owner,
          listing,
          kind,
          space.path("spaceVersion").asInt(),
          state,
          report,
          Timestamp.from(now));
    } else
      jdbc.update(
          "UPDATE placement_sets SET"
              + " resource_version=resource_version+1,space_kind=?,space_version=?,room_state_id=?,collision_report=?::jsonb,is_stale=FALSE,saved_at=?"
              + " WHERE set_id=?",
          kind,
          space.path("spaceVersion").asInt(),
          state,
          report,
          Timestamp.from(now),
          id);
    jdbc.update("DELETE FROM placement_items WHERE set_id=?", id);
    int ordinal = 0;
    for (var item : input.path("placements")) insertItem(id, ordinal++, item);
  }

  private void insertItem(UUID id, int ordinal, JsonNode item) {
    var p = item.path("transform").path("position");
    var q = item.path("transform").path("rotation");
    jdbc.update(
        "INSERT INTO placement_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        UUID.fromString(item.path("placementId").asText()),
        ordinal,
        UUID.fromString(item.path("furnitureId").asText()),
        item.path("modelVersion").asInt(),
        p.path("x").asDouble(),
        p.path("y").asDouble(),
        p.path("z").asDouble(),
        q.path("x").asDouble(),
        q.path("y").asDouble(),
        q.path("z").asDouble(),
        q.path("w").asDouble());
  }

  public void delete(UUID id) {
    jdbc.update("DELETE FROM placement_items WHERE set_id=?", id);
    jdbc.update(
        "UPDATE placement_sets SET deleted_at=CURRENT_TIMESTAMP,room_state_id=NULL WHERE set_id=?",
        id);
  }
}
