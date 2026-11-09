package com.dulleobang.capture.validation;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

public final class RigidTransformValidator {
  private RigidTransformValidator() {}

  public static void matrix(JsonNode node) {
    if (!node.isArray() || node.size() != 16) fail();
    double[] m = new double[16];
    for (int n = 0; n < 16; n++) {
      if (!node.get(n).isNumber() || !Double.isFinite(node.get(n).asDouble())) fail();
      m[n] = node.get(n).asDouble();
    }
    for (int n : new int[] {3, 7, 11, 15}) if (Math.abs(m[n] - (n == 15 ? 1 : 0)) > 1e-3) fail();
    for (int a = 0; a < 3; a++)
      for (int b = 0; b < 3; b++) {
        double dot = 0;
        for (int r = 0; r < 3; r++) dot += m[a * 4 + r] * m[b * 4 + r];
        if (Math.abs(dot - (a == b ? 1 : 0)) > 1e-3) fail();
      }
    double det =
        m[0] * (m[5] * m[10] - m[6] * m[9])
            - m[4] * (m[1] * m[10] - m[2] * m[9])
            + m[8] * (m[1] * m[6] - m[2] * m[5]);
    if (Math.abs(det - 1) > 1e-3) fail();
  }

  public static void quaternion(JsonNode q) {
    double sum = 0;
    for (String k : new String[] {"x", "y", "z", "w"})
      sum += q.path(k).asDouble() * q.path(k).asDouble();
    if (Math.abs(Math.sqrt(sum) - 1) > 1e-4) throw new ApiException(422, "INVALID_ROTATION");
  }

  private static void fail() {
    throw new ApiException(422, "INVALID_CAMERA_POSE");
  }
}
