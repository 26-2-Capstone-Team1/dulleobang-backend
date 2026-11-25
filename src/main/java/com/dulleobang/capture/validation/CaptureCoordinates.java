package com.dulleobang.capture.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class CaptureCoordinates {
  private CaptureCoordinates() {}

  public static double number(JsonNode node) {
    if (!node.isNumber() || !Double.isFinite(node.asDouble()))
      throw new ApiException(422, "INVALID_CAPTURE_METADATA");
    return node.asDouble();
  }

  public static long integer(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0)
      throw new ApiException(422, "INVALID_CAPTURE_METADATA");
    return node.asLong();
  }

  public static double[] matrix(JsonNode node) {
    double[] m = new double[16];
    if (node.isArray() && node.size() == 4 && node.get(0).isArray()) {
      for (int c = 0; c < 4; c++)
        for (int r = 0; r < 4; r++) {
          if (node.get(r).size() != 4) throw new ApiException(422, "INVALID_CAMERA_POSE");
          m[c * 4 + r] = number(node.get(r).get(c));
        }
    } else {
      if (!node.isArray() || node.size() != 16) throw new ApiException(422, "INVALID_CAMERA_POSE");
      for (int n = 0; n < 16; n++) m[n] = number(node.get(n));
    }
    RigidTransformValidator.matrix(StrictJson.parse(StrictJson.stringify(m)));
    return m;
  }

  public static double[] identity() {
    return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  public static double[] multiply(double[] a, double[] b) {
    double[] out = new double[16];
    for (int c = 0; c < 4; c++)
      for (int r = 0; r < 4; r++)
        for (int k = 0; k < 4; k++) out[c * 4 + r] += a[k * 4 + r] * b[c * 4 + k];
    return out;
  }

  public static double[] world(double[] first, double floor) {
    double length = Math.hypot(first[8], first[10]);
    if (length <= 1e-4) throw new ApiException(422, "INVALID_CAPTURE_ORIGIN");
    double c = first[10] / length, s = -first[8] / length, x = first[12], z = first[14];
    return new double[] {
      c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, -(c * x + s * z), -floor, s * x - c * z, 1
    };
  }

  public static double[] yaw(double angle, JsonNode p) {
    double a = Math.toRadians(angle), c = Math.cos(a), s = Math.sin(a);
    return new double[] {
      c,
      0,
      -s,
      0,
      0,
      1,
      0,
      0,
      s,
      0,
      c,
      0,
      number(p.path("x")),
      number(p.path("y")),
      number(p.path("z")),
      1
    };
  }

  public static Map<String, Object> transform(double[] m, double[] world) {
    double[] result = multiply(world, m);
    return Map.of(
        "position",
        Map.of("x", result[12], "y", result[13], "z", result[14]),
        "rotation",
        quaternion(result));
  }

  public static Map<String, Double> quaternion(double[] m) {
    double[][] r = {{m[0], m[4], m[8]}, {m[1], m[5], m[9]}, {m[2], m[6], m[10]}};
    double trace = r[0][0] + r[1][1] + r[2][2];
    double[] q = new double[4];
    if (trace > 0) {
      double s = Math.sqrt(trace + 1) * 2;
      q[0] = (r[2][1] - r[1][2]) / s;
      q[1] = (r[0][2] - r[2][0]) / s;
      q[2] = (r[1][0] - r[0][1]) / s;
      q[3] = s / 4;
    } else {
      int i = r[0][0] > r[1][1] ? 0 : 1;
      if (r[2][2] > r[i][i]) i = 2;
      int j = (i + 1) % 3, k = (i + 2) % 3;
      double s = Math.sqrt(Math.max(0, 1 + r[i][i] - r[j][j] - r[k][k])) * 2;
      if (s <= 0) throw new ApiException(422, "INVALID_CAMERA_POSE");
      q[i] = s / 4;
      q[j] = (r[i][j] + r[j][i]) / s;
      q[k] = (r[i][k] + r[k][i]) / s;
      q[3] = (r[k][j] - r[j][k]) / s;
    }
    double norm = Math.sqrt(Arrays.stream(q).map(x -> x * x).sum()), sign = q[3] < 0 ? -1 : 1;
    return Map.of(
        "x",
        sign * q[0] / norm,
        "y",
        sign * q[1] / norm,
        "z",
        sign * q[2] / norm,
        "w",
        sign * q[3] / norm);
  }
}
