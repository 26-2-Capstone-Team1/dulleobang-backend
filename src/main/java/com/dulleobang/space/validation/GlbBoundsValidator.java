package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/** Accumulates world-space mesh bounds and compares them to declared dimensions. */
final class GlbBoundsValidator {
  private final GlbAccessorReader accessors;
  private final JsonNode expected;
  private final double[] minimum = {
    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
  };
  private final double[] maximum = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

  GlbBoundsValidator(GlbAccessorReader accessors, JsonNode expected) {
    this.accessors = accessors;
    this.expected = expected;
  }

  void include(JsonNode mesh, double[] matrix) {
    if (expected == null) return;
    for (var primitive : mesh.path("primitives"))
      accessors.positions(
          accessors.get(primitive.path("attributes").path("POSITION")),
          point -> includePoint(point, matrix));
  }

  private void includePoint(double[] point, double[] matrix) {
    for (int row = 0; row < 3; row++) {
      double world = matrix[12 + row];
      for (int column = 0; column < 3; column++) world += matrix[column * 4 + row] * point[column];
      require(Double.isFinite(world));
      minimum[row] = Math.min(minimum[row], world);
      maximum[row] = Math.max(maximum[row], world);
    }
  }

  void validate() {
    if (expected == null) return;
    for (int index = 0; index < 3; index++) {
      String axis = new String[] {"x", "y", "z"}[index];
      if (!Double.isFinite(minimum[index])
          || Math.abs(minimum[index] - expected.path("min").path(axis).asDouble()) > .001
          || Math.abs(maximum[index] - expected.path("max").path(axis).asDouble()) > .001)
        throw new ApiException(422, "MODEL_GEOMETRY_DIMENSION_MISMATCH");
    }
  }
}
