package com.dulleobang.capture.mapper;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class RoomStructureMapper {
  private RoomStructureMapper() {}

  static List<Object> surfaces(JsonNode source, JsonNode raw, double[] world) {
    if (!source.isObject()
        || !source.path("objects").isArray()
        || !source.path("roomGeometry").isObject()) fail();
    var result = new ArrayList<Object>();
    var geometry = source.path("roomGeometry");
    double floor = number(geometry.path("floorHeight"));
    for (var wall : geometry.path("walls")) result.add(wall(wall, floor, world));
    for (String field : List.of("doors", "windows"))
      for (var item : geometry.path(field))
        result.add(
            Map.of(
                "id",
                item.path("id").asText(),
                "kind",
                field.equals("doors") ? "DOOR" : "WINDOW",
                "transform",
                transform(
                    yaw(number(item.path("rotation").path("yaw")), item.path("position")), world),
                "dimensions",
                item.path("dimensions")));
    if (raw != null)
      for (var item : raw.path("floors"))
        result.add(RawRoomPlanMapper.surface(item, "FLOOR", world));
    return result;
  }

  private static Object wall(JsonNode wall, double floor, double[] world) {
    var a = wall.path("from");
    var b = wall.path("to");
    if (a.size() != 2 || b.size() != 2) fail();
    double ax = number(a.get(0)),
        az = number(a.get(1)),
        bx = number(b.get(0)),
        bz = number(b.get(1)),
        dx = bx - ax,
        dz = bz - az,
        height = number(wall.path("height"));
    var position =
        StrictJson.parse(
            StrictJson.stringify(
                Map.of("x", (ax + bx) / 2, "y", floor + height / 2, "z", (az + bz) / 2)));
    return Map.of(
        "id",
        wall.path("id").asText(),
        "kind",
        "WALL",
        "transform",
        transform(yaw(Math.toDegrees(Math.atan2(-dz, dx)), position), world),
        "dimensions",
        Map.of("width", Math.hypot(dx, dz), "height", height, "depth", 0));
  }

  static List<Object> objects(JsonNode source, double[] world) {
    var result = new ArrayList<Object>();
    for (var item : source.path("objects"))
      result.add(
          Map.of(
              "id",
              item.path("objectId").asText(),
              "category",
              item.path("category").asText(),
              "dimensions",
              item.path("dimensions"),
              "transform",
              transform(
                  yaw(number(item.path("rotation").path("yaw")), item.path("position")), world)));
    return result;
  }

  private static void fail() {
    throw new ApiException(422, "INVALID_ROOMPLAN_JSON");
  }
}
