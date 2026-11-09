package com.dulleobang.capture.mapper;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class RawRoomPlanMapper {
  private RawRoomPlanMapper() {}

  static List<Object> surfaces(JsonNode raw, double[] world) {
    var result = new ArrayList<Object>();
    for (var entry :
        Map.of(
                "walls",
                "WALL",
                "doors",
                "DOOR",
                "windows",
                "WINDOW",
                "openings",
                "OPENING",
                "floors",
                "FLOOR")
            .entrySet())
      for (var item : raw.path(entry.getKey())) result.add(surface(item, entry.getValue(), world));
    return result;
  }

  static List<Object> objects(JsonNode raw, double[] world) {
    var result = new ArrayList<Object>();
    for (var item : raw.path("objects")) {
      var category = item.path("category");
      if (!category.isObject() || category.size() != 1) fail();
      result.add(
          Map.of(
              "id",
              item.path("identifier").asText(),
              "category",
              category.fieldNames().next(),
              "transform",
              transform(matrix(item.path("transform")), world),
              "dimensions",
              dimensions(item.path("dimensions"))));
    }
    return result;
  }

  static Object surface(JsonNode item, String kind, double[] world) {
    return Map.of(
        "id",
        item.path("identifier").asText(),
        "kind",
        kind,
        "transform",
        transform(matrix(item.path("transform")), world),
        "dimensions",
        dimensions(item.path("dimensions")));
  }

  static Object dimensions(JsonNode values) {
    if (!values.isArray() || values.size() != 3) fail();
    return Map.of(
        "width",
        number(values.get(0)),
        "height",
        number(values.get(1)),
        "depth",
        number(values.get(2)));
  }

  private static void fail() {
    throw new ApiException(422, "INVALID_ROOMPLAN_JSON");
  }
}
