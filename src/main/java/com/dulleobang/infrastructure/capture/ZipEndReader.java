package com.dulleobang.infrastructure.capture;

import static com.dulleobang.infrastructure.capture.ZipDirectoryValidator.fail;

import java.io.*;
import java.nio.*;

/** Reads the end record and checks the central-directory location and limits. */
final class ZipEndReader {
  private ZipEndReader() {}

  record Directory(int count, long offset, long length) {}

  static Directory read(RandomAccessFile file) throws IOException {
    long size = file.length();
    if (size > 2L * 1024 * 1024 * 1024) fail(413, "CAPTURE_ZIP_TOO_LARGE");
    int tail = (int) Math.min(size, 65557);
    byte[] bytes = new byte[tail];
    file.seek(size - tail);
    file.readFully(bytes);
    var end = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int position = findEnd(end, tail);
    if (position < 0 || end.getShort(position + 4) != 0 || end.getShort(position + 6) != 0)
      fail(422, "INVALID_CAPTURE_ZIP");
    int count = Short.toUnsignedInt(end.getShort(position + 10));
    if (count > 7000) fail(413, "CAPTURE_ZIP_TOO_LARGE");
    if (count != Short.toUnsignedInt(end.getShort(position + 8))) fail(422, "INVALID_CAPTURE_ZIP");
    long offset = Integer.toUnsignedLong(end.getInt(position + 16));
    long length = Integer.toUnsignedLong(end.getInt(position + 12));
    if (offset + length > size - tail + position) fail(422, "INVALID_CAPTURE_ZIP");
    return new Directory(count, offset, length);
  }

  private static int findEnd(ByteBuffer bytes, int tail) {
    for (int index = tail - 22; index >= 0; index--)
      if (bytes.getInt(index) == 0x06054b50
          && index + 22 + Short.toUnsignedInt(bytes.getShort(index + 20)) == tail) return index;
    return -1;
  }
}
