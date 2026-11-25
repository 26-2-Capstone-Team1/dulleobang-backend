package com.dulleobang.placement.repository;

import com.dulleobang.common.exception.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlacementReferenceRepository {
  private final JdbcTemplate jdbc;

  public PlacementReferenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void requireSpace(UUID listing, int version) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM spaces WHERE listing_id=? AND space_version=?)",
            Boolean.class,
            listing,
            version))) throw new ApiException(404, "RESOURCE_NOT_FOUND");
  }

  public void requireState(UUID actor, UUID state, UUID listing, int version) {
    var rows =
        jdbc.query(
            "SELECT listing_id,source_space_version,status FROM room_states WHERE state_id=? AND"
                + " owner_id=? AND deleted_at IS NULL FOR UPDATE",
            (rs, n) ->
                Map.of(
                    "listing",
                    rs.getObject(1, UUID.class),
                    "version",
                    rs.getInt(2),
                    "status",
                    rs.getString(3)),
            state,
            actor);
    if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    var found = rows.getFirst();
    if (!found.get("listing").equals(listing)
        || !found.get("version").equals(version)
        || !found.get("status").equals("READY"))
      throw new ApiException(409, "ROOM_STATE_NOT_READY");
  }
}
