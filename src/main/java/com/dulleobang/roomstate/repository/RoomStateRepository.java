package com.dulleobang.roomstate.repository;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.roomstate.domain.RoomStateRecord;
import com.dulleobang.space.domain.SpaceRecord;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomStateRepository {
  private final JdbcTemplate jdbc;

  public RoomStateRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<RoomStateRecord> find(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM room_states WHERE state_id=?" + (lock ? " FOR UPDATE" : ""),
            this::map,
            id)
        .stream()
        .findFirst();
  }

  public List<RoomStateRecord> list(UUID actor, UUID listing) {
    return jdbc.query(
        "SELECT * FROM room_states WHERE owner_id=? AND listing_id=? AND deleted_at IS NULL ORDER"
            + " BY created_at DESC,state_id",
        this::map,
        actor,
        listing);
  }

  public void create(UUID id, UUID actor, SpaceRecord source, List<UUID> removed, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " room_states(state_id,owner_id,listing_id,source_space_version,source_objects_version,removed_object_ids,source_bundle,created_at)"
            + " VALUES(?,?,?,?,?,?::uuid[],?::jsonb,?)",
        id,
        actor,
        source.listingId(),
        source.spaceVersion(),
        source.objectsVersion(),
        "{" + String.join(",", removed.stream().map(UUID::toString).toList()) + "}",
        StrictJson.stringify(source.bundle()),
        java.sql.Timestamp.from(now));
  }

  public boolean referenced(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM placement_sets WHERE room_state_id=? AND deleted_at IS"
                + " NULL)",
            Boolean.class,
            id));
  }

  public void delete(UUID id, Instant now) {
    jdbc.update(
        "UPDATE room_states SET deleted_at=? WHERE state_id=?", java.sql.Timestamp.from(now), id);
  }

  private RoomStateRecord map(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
    Object[] raw = (Object[]) rs.getArray("removed_object_ids").getArray();
    var removed = Arrays.stream(raw).map(x -> UUID.fromString(x.toString())).toList();
    String bundle = rs.getString("bundle");
    return new RoomStateRecord(
        rs.getObject("state_id", UUID.class),
        rs.getObject("owner_id", UUID.class),
        rs.getObject("listing_id", UUID.class),
        rs.getInt("source_space_version"),
        rs.getInt("source_objects_version"),
        removed,
        rs.getString("status"),
        rs.getObject("job_id", UUID.class),
        bundle == null ? null : StrictJson.parse(bundle),
        StrictJson.parse(rs.getString("source_bundle")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("deleted_at") != null);
  }
}
