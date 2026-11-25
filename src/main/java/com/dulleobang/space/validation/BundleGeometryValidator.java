package com.dulleobang.space.validation;

import com.dulleobang.capture.validation.RigidTransformValidator;
import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class BundleGeometryValidator {
  private final RoomGeometryValidator geometry;
  private final OpeningValidator openings;

  public BundleGeometryValidator(RoomGeometryValidator geometry, OpeningValidator openings) {
    this.geometry = geometry;
    this.openings = openings;
  }

  public void validate(JsonNode bundle, Set<String> collisionNodes) {
    var g = bundle.path("geometry");
    for (var entry :
        Map.of(
                "rooms",
                "roomId",
                "doors",
                "doorId",
                "windows",
                "windowId",
                "structuralColliders",
                "colliderId")
            .entrySet()) unique(g.path(entry.getKey()), entry.getValue());
    unique(bundle.path("objects"), "objectId");
    var rooms = geometry.rooms(bundle);
    var referenced = new ArrayList<String>();
    for (var object : bundle.path("objects")) {
      require(rooms.containsKey(object.path("roomId").asText()), "INVALID_ROOM_REFERENCE");
      RigidTransformValidator.quaternion(object.path("transform").path("rotation"));
      BoundsValidator.box(object.path("dimensions"), object.path("boundingBox"));
      referenced.add(object.path("collisionNode").asText());
    }
    openings.validate(g, rooms, referenced);
    for (var collider : g.path("structuralColliders")) {
      require(rooms.containsKey(collider.path("roomId").asText()), "INVALID_ROOM_REFERENCE");
      referenced.add(collider.path("nodeName").asText());
    }
    require(
        referenced.size() == new HashSet<>(referenced).size()
            && collisionNodes.containsAll(referenced),
        "INVALID_COLLISION_REFERENCE");
  }

  private static void unique(JsonNode values, String key) {
    Set<String> seen = new HashSet<>();
    for (var value : values) require(seen.add(value.path(key).asText()), "DUPLICATE_ID");
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
