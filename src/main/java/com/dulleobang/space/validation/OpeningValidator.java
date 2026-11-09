package com.dulleobang.space.validation;

import com.dulleobang.capture.validation.RigidTransformValidator;
import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OpeningValidator {
  public void validate(JsonNode geometry, Map<String, JsonNode> rooms, List<String> referenced) {
    doors(geometry, rooms, referenced);
    windows(geometry, rooms);
  }

  private void doors(JsonNode geometry, Map<String, JsonNode> rooms, List<String> referenced) {
    for (var door : geometry.path("doors")) {
      require(rooms.containsKey(door.path("roomId").asText()), "INVALID_ROOM_REFERENCE");
      RigidTransformValidator.quaternion(door.path("transform").path("rotation"));
      if (door.path("openingType").asText().equals("HINGED"))
        require(
            !door.path("hingePosition").isNull() && !door.path("sweepNode").isNull(),
            "INVALID_DOOR");
      if (!door.path("sweepNode").isNull()) referenced.add(door.path("sweepNode").asText());
    }
  }

  private void windows(JsonNode geometry, Map<String, JsonNode> rooms) {
    for (var window : geometry.path("windows")) {
      var room = rooms.get(window.path("roomId").asText());
      require(room != null, "INVALID_ROOM_REFERENCE");
      var q = window.path("transform").path("rotation");
      RigidTransformValidator.quaternion(q);
      require(
          Math.abs(q.path("x").asDouble()) <= 1e-4 && Math.abs(q.path("z").asDouble()) <= 1e-4,
          "INVALID_WINDOW_ROTATION");
      double y = window.path("transform").path("position").path("y").asDouble(),
          height = window.path("height").asDouble();
      require(
          Math.abs(
                      y
                          - room.path("floorY").asDouble()
                          - window.path("sillHeight").asDouble()
                          - height / 2)
                  <= .001
              && y + height / 2 <= room.path("ceilingY").asDouble() + .001,
          "INVALID_WINDOW_HEIGHT");
    }
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
