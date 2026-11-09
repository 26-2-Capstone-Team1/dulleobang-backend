package com.dulleobang.space.mapper;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class MobileSpaceMapper {
  private MobileSpaceMapper() {}

  public static Map<String, Object> project(Map<String, Object> value, boolean space) {
    var result = new LinkedHashMap<>(value);
    Object raw = result.remove("bundle");
    if (raw == null) return result;
    JsonNode bundle =
        raw instanceof JsonNode node ? node : StrictJson.parse(StrictJson.stringify(raw));
    boolean points = bundle.path("sceneAsset").path("format").asText().equals("POINT_CLOUD_PLY_V1");
    result.put(
        space ? "3dgsUri" : "sceneUri",
        points ? null : bundle.path("sceneAsset").path("url").asText());
    if (points) result.put("pointsUri", bundle.path("sceneAsset").path("url").asText());
    result.put("collisionGeometryUri", bundle.path("collisionAsset").path("url").asText());
    result.put("roomGeometry", geometry(bundle.path("geometry")));
    result.put("roomDimensions", bundle.path("roomDimensions"));
    result.put(space ? "objects" : "remainingObjects", objects(bundle.path("objects")));
    if (bundle.has("meshSource")) {
      result.put("roomGeometry", bundle.path("meshSource").path("roomGeometry"));
      result.put("glass", bundle.path("meshSource").path("glass"));
    }
    return result;
  }

  public static List<Map<String, Object>> objects(JsonNode objects) {
    var result = new ArrayList<Map<String, Object>>();
    for (var object : objects) result.add(object(object));
    return result;
  }

  public static Map<String, Object> object(JsonNode object) {
    var rotation = object.path("transform").path("rotation");
    var position = object.path("transform").path("position");
    double x = rotation.path("x").asDouble(),
        y = rotation.path("y").asDouble(),
        z = rotation.path("z").asDouble(),
        w = rotation.path("w").asDouble(),
        half = object.path("dimensions").path("height").asDouble() / 2;
    var center =
        Map.of(
            "x",
            position.path("x").asDouble() + half * 2 * (x * y - w * z),
            "y",
            position.path("y").asDouble() + half * (1 - 2 * (x * x + z * z)),
            "z",
            position.path("z").asDouble() + half * 2 * (y * z + w * x));
    String category =
        switch (object.path("category").asText()) {
          case "WARDROBE" -> "closet";
          case "SHELF" -> "storage";
          default -> object.path("category").asText().toLowerCase(Locale.ROOT);
        };
    return Map.of(
        "objectId",
        object.path("objectId").asText(),
        "category",
        category,
        "position",
        center,
        "rotation",
        Map.of("yaw", yaw(rotation)),
        "dimensions",
        object.path("dimensions"),
        "ownershipType",
        object.path("ownershipType").asText(),
        "removable",
        object.path("removable").asBoolean());
  }

  public static double yaw(JsonNode rotation) {
    double x = rotation.path("x").asDouble(),
        y = rotation.path("y").asDouble(),
        z = rotation.path("z").asDouble(),
        w = rotation.path("w").asDouble();
    return Math.toDegrees(Math.atan2(2 * (w * y + x * z), 1 - 2 * (y * y + z * z)));
  }

  public static Map<String, Object> geometry(JsonNode geometry) {
    double floor = Double.POSITIVE_INFINITY,
        ceiling = Double.NEGATIVE_INFINITY,
        minX = Double.POSITIVE_INFINITY,
        maxX = Double.NEGATIVE_INFINITY,
        minZ = Double.POSITIVE_INFINITY,
        maxZ = Double.NEGATIVE_INFINITY;
    var walls = new ArrayList<Object>();
    for (var room : geometry.path("rooms")) {
      floor = Math.min(floor, room.path("floorY").asDouble());
      ceiling = Math.max(ceiling, room.path("ceilingY").asDouble());
      var polygon = room.path("floorPolygon");
      for (int i = 0; i < polygon.size(); i++) {
        var point = polygon.get(i);
        minX = Math.min(minX, point.get(0).asDouble());
        maxX = Math.max(maxX, point.get(0).asDouble());
        minZ = Math.min(minZ, point.get(1).asDouble());
        maxZ = Math.max(maxZ, point.get(1).asDouble());
        walls.add(
            Map.of(
                "id",
                room.path("roomId").asText() + ":" + i,
                "from",
                point,
                "to",
                polygon.get((i + 1) % polygon.size()),
                "height",
                room.path("ceilingY").asDouble() - room.path("floorY").asDouble()));
      }
    }
    return Map.of(
        "floorHeight",
        floor,
        "ceilingHeight",
        ceiling - floor,
        "walls",
        walls,
        "doors",
        openings(geometry, "doors", "door"),
        "windows",
        openings(geometry, "windows", "window"),
        "boundingBox",
        Map.of(
            "min",
            Map.of("x", minX, "y", floor, "z", minZ),
            "max",
            Map.of("x", maxX, "y", ceiling, "z", maxZ)));
  }

  private static List<Object> openings(JsonNode geometry, String key, String kind) {
    var result = new ArrayList<Object>();
    for (var value : geometry.path(key))
      result.add(
          Map.of(
              "id",
              value.path(kind + "Id").asText(),
              "type",
              kind,
              "position",
              value.path("transform").path("position"),
              "rotation",
              Map.of("yaw", yaw(value.path("transform").path("rotation"))),
              "dimensions",
              Map.of(
                  "width",
                  value.path("width").asDouble(),
                  "height",
                  value.path("height").asDouble(),
                  "depth",
                  .1)));
    return result;
  }
}
