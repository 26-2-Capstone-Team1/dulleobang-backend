package com.dulleobang.placement.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.placement.domain.PlacementRecord;

public final class PlacementPreconditions {
  private PlacementPreconditions() {}

  public static void check(PlacementRecord existing, String match, String none) {
    if (match != null && none != null) throw new ApiException(400, "INVALID_PRECONDITION");
    if (match == null && none == null) throw new ApiException(428, "PRECONDITION_REQUIRED");
    if (none != null) {
      if (!none.equals("*")) throw new ApiException(400, "INVALID_PRECONDITION");
      if (existing != null) throw new ApiException(412, "PRECONDITION_FAILED");
    } else {
      if (existing == null) throw new ApiException(412, "PRECONDITION_FAILED");
      ETagParser.requireMatch(match, existing.version());
    }
  }
}
