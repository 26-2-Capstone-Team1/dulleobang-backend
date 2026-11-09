package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.BoundsValidator.dimensions;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RoomGeometryValidator {
  public Map<String, JsonNode> rooms(JsonNode bundle) {
    Map<String, JsonNode> rooms = new HashMap<>();
    double minX = Double.POSITIVE_INFINITY,
        minZ = minX,
        minY = minX,
        maxX = -Double.MAX_VALUE,
        maxZ = maxX,
        maxY = maxX;
    for (var room : bundle.path("geometry").path("rooms")) {
      rooms.put(room.path("roomId").asText(), room);
      polygon(room);
      minY = Math.min(minY, room.path("floorY").asDouble());
      maxY = Math.max(maxY, room.path("ceilingY").asDouble());
      for (var p : room.path("floorPolygon")) {
        minX = Math.min(minX, p.get(0).asDouble());
        maxX = Math.max(maxX, p.get(0).asDouble());
        minZ = Math.min(minZ, p.get(1).asDouble());
        maxZ = Math.max(maxZ, p.get(1).asDouble());
      }
    }
    dimensions(
        bundle.path("roomDimensions"),
        maxX - minX,
        maxY - minY,
        maxZ - minZ,
        "ROOM_DIMENSION_MISMATCH");
    return rooms;
  }

  private void polygon(JsonNode room) {
    var polygon = room.path("floorPolygon");
    var unique = new HashSet<String>();
    double area = 0,
        minX = Double.POSITIVE_INFINITY,
        minZ = minX,
        maxX = -Double.MAX_VALUE,
        maxZ = maxX;
    for (int n = 0; n < polygon.size(); n++) {
      var p = polygon.get(n);
      var next = polygon.get((n + 1) % polygon.size());
      require(unique.add(p.toString()), "INVALID_FLOOR_POLYGON");
      area +=
          p.get(0).asDouble() * next.get(1).asDouble()
              - next.get(0).asDouble() * p.get(1).asDouble();
      minX = Math.min(minX, p.get(0).asDouble());
      maxX = Math.max(maxX, p.get(0).asDouble());
      minZ = Math.min(minZ, p.get(1).asDouble());
      maxZ = Math.max(maxZ, p.get(1).asDouble());
    }
    area /= 2;
    require(
        area < 0 && Math.abs(-area - room.path("areaM2").asDouble()) <= .01,
        "INVALID_FLOOR_POLYGON");
    double height = room.path("ceilingY").asDouble() - room.path("floorY").asDouble();
    require(
        height > 0 && Math.abs(room.path("dimensions").path("height").asDouble() - height) <= .001,
        "INVALID_ROOM_HEIGHT");
    dimensions(
        room.path("dimensions"), maxX - minX, height, maxZ - minZ, "ROOM_DIMENSION_MISMATCH");
    for (int i = 0; i < polygon.size(); i++)
      for (int j = i + 1; j < polygon.size(); j++)
        if (j != i + 1 && !(i == 0 && j == polygon.size() - 1))
          require(
              !intersects(
                  polygon.get(i),
                  polygon.get((i + 1) % polygon.size()),
                  polygon.get(j),
                  polygon.get((j + 1) % polygon.size())),
              "INVALID_FLOOR_POLYGON");
  }

  private double cross(JsonNode a, JsonNode b, JsonNode c) {
    return (b.get(0).asDouble() - a.get(0).asDouble()) * (c.get(1).asDouble() - a.get(1).asDouble())
        - (b.get(1).asDouble() - a.get(1).asDouble()) * (c.get(0).asDouble() - a.get(0).asDouble());
  }

  private boolean on(JsonNode a, JsonNode b, JsonNode p) {
    return Math.abs(cross(a, b, p)) < 1e-9
        && p.get(0).asDouble() >= Math.min(a.get(0).asDouble(), b.get(0).asDouble()) - 1e-9
        && p.get(0).asDouble() <= Math.max(a.get(0).asDouble(), b.get(0).asDouble()) + 1e-9
        && p.get(1).asDouble() >= Math.min(a.get(1).asDouble(), b.get(1).asDouble()) - 1e-9
        && p.get(1).asDouble() <= Math.max(a.get(1).asDouble(), b.get(1).asDouble()) + 1e-9;
  }

  private boolean intersects(JsonNode a, JsonNode b, JsonNode c, JsonNode d) {
    return cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0
        || on(a, b, c)
        || on(a, b, d)
        || on(c, d, a)
        || on(c, d, b);
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
