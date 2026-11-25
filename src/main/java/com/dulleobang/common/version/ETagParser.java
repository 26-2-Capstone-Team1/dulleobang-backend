package com.dulleobang.common.version;

import com.dulleobang.common.exception.ApiException;

public final class ETagParser {
  private ETagParser() {}

  public static long requireMatch(String supplied, long version) {
    if (supplied == null || supplied.isBlank())
      throw new ApiException(428, "PRECONDITION_REQUIRED");
    if (!supplied.equals("\"" + version + "\"")) throw new ApiException(412, "VERSION_MISMATCH");
    return version;
  }

  public static void requireCreate(String supplied, boolean exists) {
    if (supplied == null) throw new ApiException(428, "PRECONDITION_REQUIRED");
    if (!supplied.equals("*") || exists) throw new ApiException(412, "VERSION_MISMATCH");
  }
}
