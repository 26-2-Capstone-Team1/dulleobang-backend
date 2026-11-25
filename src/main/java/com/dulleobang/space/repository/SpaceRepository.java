package com.dulleobang.space.repository;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.space.domain.SpaceRecord;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpaceRepository {
  private final JdbcTemplate jdbc;

  public SpaceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<SpaceRecord> find(UUID listing, int version, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM spaces WHERE listing_id=? AND space_version=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new SpaceRecord(
                    rs.getObject("listing_id", UUID.class),
                    rs.getInt("space_version"),
                    rs.getInt("objects_version"),
                    (ObjectNode) StrictJson.parse(rs.getString("bundle")),
                    rs.getTimestamp("created_at").toInstant()),
            listing,
            version)
        .stream()
        .findFirst();
  }

  public SpaceRecord classify(SpaceRecord space, ObjectNode bundle) {
    jdbc.update(
        "UPDATE spaces SET objects_version=objects_version+1,bundle=?::jsonb WHERE listing_id=? AND"
            + " space_version=?",
        StrictJson.stringify(bundle),
        space.listingId(),
        space.spaceVersion());
    return find(space.listingId(), space.spaceVersion(), false).orElseThrow();
  }
}
