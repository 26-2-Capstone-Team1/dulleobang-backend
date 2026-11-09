package com.dulleobang.job.repository;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobInputRepository {
  private final JdbcTemplate jdbc;

  public JobInputRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JsonNode sourceBundle(UUID id) {
    return jdbc.queryForObject(
        "SELECT source_bundle FROM room_states WHERE state_id=?",
        (rs, n) -> StrictJson.parse(rs.getString(1)),
        id);
  }

  public Map<String, Object> removal(UUID id) {
    return jdbc.queryForObject(
        "SELECT * FROM room_states WHERE state_id=?",
        (rs, n) ->
            Map.of(
                "roomStateId",
                id,
                "sourceSpaceVersion",
                rs.getInt("source_space_version"),
                "sourceObjectsVersion",
                rs.getInt("source_objects_version"),
                "removedObjectIds",
                List.of((UUID[]) rs.getArray("removed_object_ids").getArray())),
        id);
  }
}
