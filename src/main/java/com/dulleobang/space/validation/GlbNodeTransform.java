package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;

import com.dulleobang.capture.validation.CaptureCoordinates;
import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;

/** Decodes and checks glTF local transforms independently of scene traversal. */
final class GlbNodeTransform {
  private GlbNodeTransform() {}

  static boolean identity(double[] matrix) {
    var expected = CaptureCoordinates.identity();
    for (int n = 0; n < 16; n++) if (Math.abs(matrix[n] - expected[n]) > 1e-6) return false;
    return true;
  }

  private static double[] vector(JsonNode node, String field, double[] defaults) {
    if (!node.has(field)) return defaults;
    var value = node.get(field);
    require(value.isArray() && value.size() == defaults.length);
    double[] result = new double[defaults.length];
    for (int n = 0; n < result.length; n++) {
      require(value.get(n).isNumber() && Double.isFinite(value.get(n).asDouble()));
      result[n] = value.get(n).asDouble();
    }
    return result;
  }

  static double[] transform(JsonNode node) {
    require(
        !node.has("matrix")
            || !node.has("translation") && !node.has("rotation") && !node.has("scale"));
    if (node.has("matrix")) {
      double[] m = vector(node, "matrix", CaptureCoordinates.identity());
      for (int n : new int[] {3, 7, 11, 15}) require(Math.abs(m[n] - (n == 15 ? 1 : 0)) <= 1e-6);
      return m;
    }
    double[] t = vector(node, "translation", new double[] {0, 0, 0}),
        s = vector(node, "scale", new double[] {1, 1, 1}),
        r = vector(node, "rotation", new double[] {0, 0, 0, 1});
    double norm = Math.sqrt(Arrays.stream(r).map(x -> x * x).sum());
    if (Math.abs(norm - 1) > 1e-4) throw new ApiException(422, "INVALID_ROTATION");
    double x = r[0], y = r[1], z = r[2], w = r[3];
    return new double[] {
      (1 - 2 * y * y - 2 * z * z) * s[0],
      (2 * x * y + 2 * z * w) * s[0],
      (2 * x * z - 2 * y * w) * s[0],
      0,
      (2 * x * y - 2 * z * w) * s[1],
      (1 - 2 * x * x - 2 * z * z) * s[1],
      (2 * y * z + 2 * x * w) * s[1],
      0,
      (2 * x * z + 2 * y * w) * s[2],
      (2 * y * z - 2 * x * w) * s[2],
      (1 - 2 * x * x - 2 * y * y) * s[2],
      0,
      t[0],
      t[1],
      t[2],
      1
    };
  }
}
