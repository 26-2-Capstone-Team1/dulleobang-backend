package com.dulleobang.space.validation;

import com.dulleobang.common.exception.ApiException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** PR #2 compatibility: real coloured surface points, never treated as Gaussian splats. */
public final class PointCloudValidator {
  private PointCloudValidator() {}

  public static int validate(byte[] raw) {
    try {
      String header = new String(raw, 0, Math.min(raw.length, 4096), StandardCharsets.US_ASCII);
      int end = header.indexOf("end_header\n");
      if (end < 0) throw new IllegalArgumentException();
      String[] lines = header.substring(0, end).split("\n");
      if (lines.length != 9
          || !lines[0].equals("ply")
          || !lines[1].equals("format binary_little_endian 1.0")
          || !lines[2].startsWith("element vertex ")) throw new IllegalArgumentException();
      String[] expected = {
        "float x", "float y", "float z", "uchar red", "uchar green", "uchar blue"
      };
      for (int i = 0; i < 6; i++)
        if (!lines[i + 3].equals("property " + expected[i])) throw new IllegalArgumentException();
      int count = Integer.parseInt(lines[2].substring(15));
      if (count < 1 || count > 2_000_000 || raw.length - end - 11 != count * 15)
        throw new IllegalArgumentException();
      var bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      for (int n = 0; n < count; n++)
        for (int axis = 0; axis < 3; axis++) {
          float value = bytes.getFloat(end + 11 + n * 15 + axis * 4);
          if (!Float.isFinite(value) || Math.abs(value) > 1000)
            throw new IllegalArgumentException();
        }
      return count;
    } catch (Exception ex) {
      throw new ApiException(422, "INVALID_ARTIFACT");
    }
  }
}
