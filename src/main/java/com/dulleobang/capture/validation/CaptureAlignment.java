package com.dulleobang.capture.validation;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.capture.domain.*;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

record CaptureAlignment(double[] world, double[] roomWorld) {
  static CaptureAlignment compute(
      JsonNode meta, JsonNode poses, JsonNode structure, JsonNode rawRoom) {
    double floor =
        structure != null
            ? number(structure.path("roomGeometry").path("floorHeight"))
            : meta.path("floorHeightM").isMissingNode() || meta.path("floorHeightM").isNull()
                ? 0
                : number(meta.path("floorHeightM"));
    double[] first = matrix(poses.get(0).path("T_wc")),
        alignment = meta.has("roomPlanToWorld") ? matrix(meta.path("roomPlanToWorld")) : identity();
    if (structure != null || rawRoom != null) {
      for (int n : new int[] {1, 4, 6, 9, 5})
        require(Math.abs(alignment[n] - (n == 5 ? 1 : 0)) <= 1e-6, "INVALID_CAPTURE_ORIGIN");
      floor = alignment[5] * floor + alignment[13];
    }
    double[] world = CaptureCoordinates.world(first, floor), roomWorld = multiply(world, alignment);
    return new CaptureAlignment(world, roomWorld);
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
