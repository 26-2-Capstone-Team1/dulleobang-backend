package com.dulleobang.space.validation;

import com.dulleobang.common.exception.ApiException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class PlyValidator {
  private static final String[] FIELDS =
      "x y z f_dc_0 f_dc_1 f_dc_2 opacity scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3"
          .split(" ");

  private PlyValidator() {}

  public static int validate(byte[] raw) {
    try {
      String prefix = new String(raw, 0, Math.min(raw.length, 16384), StandardCharsets.ISO_8859_1);
      int end = prefix.indexOf("end_header\n");
      if (end < 0) throw new IllegalArgumentException();
      String[] lines = prefix.substring(0, end).split("\n");
      if (!lines[0].equals("ply") || !lines[1].equals("format binary_little_endian 1.0"))
        throw new IllegalArgumentException();
      var properties =
          java.util.Arrays.stream(lines).filter(s -> s.startsWith("property ")).toList();
      if (properties.size() != 14
          || !java.util.Arrays.asList(lines).contains("comment dulleobang_gs_ply_v1"))
        throw new IllegalArgumentException();
      var elements = java.util.Arrays.stream(lines).filter(s -> s.startsWith("element ")).toList();
      if (elements.size() != 1 || !elements.getFirst().startsWith("element vertex "))
        throw new IllegalArgumentException();
      int count = Integer.parseInt(elements.getFirst().substring(15));
      if (count < 1 || count > 2_000_000 || raw.length - (end + 11) != count * 56)
        throw new IllegalArgumentException();
      for (int i = 0; i < 14; i++)
        if (!properties.get(i).equals("property float " + FIELDS[i]))
          throw new IllegalArgumentException();
      var buffer =
          ByteBuffer.wrap(raw, end + 11, raw.length - end - 11).order(ByteOrder.LITTLE_ENDIAN);
      for (int row = 0; row < count; row++) {
        double norm = 0;
        for (int i = 0; i < 14; i++) {
          float value = buffer.getFloat();
          if (!Float.isFinite(value)) throw new IllegalArgumentException();
          if (i >= 10) norm += value * value;
        }
        if (Math.abs(Math.sqrt(norm) - 1) > 1e-4) throw new ApiException(422, "INVALID_ROTATION");
      }
      return count;
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(422, "INVALID_ARTIFACT");
    }
  }
}
