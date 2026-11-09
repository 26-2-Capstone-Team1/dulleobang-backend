package com.dulleobang.upload.image;

import java.nio.*;

/** Reads only the TIFF orientation tag; no original metadata is copied to output. */
final class ExifOrientation {
  private ExifOrientation() {}

  static int read(byte[] raw) {
    try {
      if (raw.length > 4 && (raw[0] & 255) == 255 && (raw[1] & 255) == 216) {
        int offset = 2;
        while (offset + 4 <= raw.length && (raw[offset] & 255) == 255) {
          int marker = raw[offset + 1] & 255;
          if (marker == 218 || marker == 217) break;
          int size = ((raw[offset + 2] & 255) << 8) | (raw[offset + 3] & 255);
          if (size < 2 || (long) offset + 2 + size > raw.length) break;
          if (marker == 225
              && size >= 8
              && raw[offset + 4] == 'E'
              && raw[offset + 5] == 'x'
              && raw[offset + 6] == 'i'
              && raw[offset + 7] == 'f') return tiff(raw, offset + 10, size - 8);
          offset += size + 2;
        }
      } else if (raw.length > 8 && raw[0] == (byte) 137) {
        int offset = 8;
        while (offset + 12 <= raw.length) {
          int size = ByteBuffer.wrap(raw, offset, 4).getInt();
          if (size < 0 || (long) offset + size + 12 > raw.length) break;
          if (raw[offset + 4] == 'e'
              && raw[offset + 5] == 'X'
              && raw[offset + 6] == 'I'
              && raw[offset + 7] == 'f') return tiff(raw, offset + 8, size);
          offset += size + 12;
        }
      }
    } catch (IndexOutOfBoundsException | IllegalArgumentException ignored) {
      return 1;
    }
    return 1;
  }

  private static int tiff(byte[] data, int start, int length) {
    var b = ByteBuffer.wrap(data, start, length).slice();
    if (length < 8) return 1;
    if (b.get(0) == 'I' && b.get(1) == 'I') b.order(ByteOrder.LITTLE_ENDIAN);
    else if (b.get(0) == 'M' && b.get(1) == 'M') b.order(ByteOrder.BIG_ENDIAN);
    else return 1;
    if (Short.toUnsignedInt(b.getShort(2)) != 42) return 1;
    int offset = b.getInt(4);
    if (offset < 8 || offset > length - 2) return 1;
    int count = Short.toUnsignedInt(b.getShort(offset));
    for (int n = 0; n < count; n++) {
      long position = (long) offset + 2 + 12L * n;
      if (position > length - 12) break;
      int p = (int) position;
      if (Short.toUnsignedInt(b.getShort(p)) == 274
          && Short.toUnsignedInt(b.getShort(p + 2)) == 3
          && b.getInt(p + 4) == 1) {
        int value = Short.toUnsignedInt(b.getShort(p + 8));
        return value >= 1 && value <= 8 ? value : 1;
      }
    }
    return 1;
  }
}
