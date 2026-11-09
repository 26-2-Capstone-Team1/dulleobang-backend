package com.dulleobang.placement.mapper;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.furniture.domain.FurnitureItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MobilePlacementMapper {
  public ObjectNode canonical(JsonNode input, int version, Map<UUID, FurnitureItem> models) {
    var value = JsonNodeFactory.instance.objectNode();
    value
        .putObject("space")
        .put("kind", "ORIGINAL")
        .put("spaceVersion", version)
        .putNull("roomStateId");
    value.putNull("clientCollisionReport");
    var items = value.putArray("placements");
    for (var source : input.path("placements")) {
      var furniture = models.get(UUID.fromString(source.path("furnitureId").asText()));
      if (furniture.model() == null) throw new ApiException(409, "FURNITURE_NOT_READY");
      var item = items.addObject();
      String id = source.path("furnitureId").asText();
      item.put("placementId", id)
          .put("furnitureId", id)
          .put("modelVersion", furniture.model().path("modelVersion").asInt());
      var transform = item.putObject("transform");
      transform.set("position", source.path("position").deepCopy());
      double yaw = Math.toRadians(source.path("rotation").path("yaw").asDouble() % 360) / 2;
      transform
          .putObject("rotation")
          .put("x", 0)
          .put("y", Math.sin(yaw))
          .put("z", 0)
          .put("w", Math.cos(yaw));
    }
    return value;
  }

  public ObjectNode response(JsonNode canonical) {
    var value = JsonNodeFactory.instance.objectNode();
    value.set("placementSetId", canonical.path("placementSetId"));
    value.set("savedAt", canonical.path("savedAt"));
    var items = value.putArray("placements");
    for (var placement : canonical.path("placements")) {
      var item = items.addObject();
      item.set("furnitureId", placement.path("furnitureId"));
      item.set("position", placement.path("transform").path("position"));
      var q = placement.path("transform").path("rotation");
      double x = q.path("x").asDouble(),
          y = q.path("y").asDouble(),
          z = q.path("z").asDouble(),
          w = q.path("w").asDouble();
      item.putObject("rotation")
          .put("yaw", Math.toDegrees(Math.atan2(2 * (w * y + x * z), 1 - 2 * (y * y + z * z))));
    }
    return value;
  }
}
