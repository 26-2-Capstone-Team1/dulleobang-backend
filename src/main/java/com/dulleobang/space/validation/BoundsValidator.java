package com.dulleobang.space.validation;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class BoundsValidator {
  private BoundsValidator() {}

  public static void box(JsonNode dimensions, JsonNode box) {
    double w = dimensions.path("width").asDouble(),
        h = dimensions.path("height").asDouble(),
        d = dimensions.path("depth").asDouble();
    double[] min = {-w / 2, 0, -d / 2}, max = {w / 2, h, d / 2};
    for (int n = 0; n < 3; n++) {
      String axis = new String[] {"x", "y", "z"}[n];
      require(
          Math.abs(box.path("min").path(axis).asDouble() - min[n]) <= .001
              && Math.abs(box.path("max").path(axis).asDouble() - max[n]) <= .001,
          "INVALID_BOUNDING_BOX");
    }
  }

  public static void dimensions(JsonNode node, double w, double h, double d, String code) {
    require(
        Double.isFinite(w)
            && Math.abs(node.path("width").asDouble() - w) <= .001
            && Math.abs(node.path("height").asDouble() - h) <= .001
            && Math.abs(node.path("depth").asDouble() - d) <= .001,
        code);
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
