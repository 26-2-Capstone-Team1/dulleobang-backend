package com.dulleobang.infrastructure.capture;

import static com.dulleobang.infrastructure.capture.ZipDirectoryValidator.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.Set;

/** Checks each directory entry before ZipFile can extract its contents. */
final class ZipEntryValidator {
  private ZipEntryValidator() {}

  static long read(RandomAccessFile file, Set<String> names, long expanded) throws IOException {
    byte[] header = new byte[46];
    file.readFully(header);
    var entry = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    if (entry.getInt() != 0x02014b50) fail(422, "INVALID_CAPTURE_ZIP");
    expanded += Integer.toUnsignedLong(entry.getInt(24));
    if (expanded > 5L * 1024 * 1024 * 1024) fail(413, "CAPTURE_ZIP_TOO_LARGE");
    checkFlags(entry);
    String name = readName(file, entry);
    safe(name);
    if (!name.endsWith("/") && !names.add(name)) fail(422, "DUPLICATE_ZIP_ENTRY");
    int extra = Short.toUnsignedInt(entry.getShort(30));
    int comment = Short.toUnsignedInt(entry.getShort(32));
    file.seek(file.getFilePointer() + extra + comment);
    return expanded;
  }

  private static void checkFlags(ByteBuffer entry) {
    int flags = Short.toUnsignedInt(entry.getShort(8));
    int method = Short.toUnsignedInt(entry.getShort(10));
    int mode = (int) (Integer.toUnsignedLong(entry.getInt(38)) >>> 16);
    if ((flags & 1) != 0 || (mode & 0170000) == 0120000) fail(422, "UNSAFE_CAPTURE_ZIP");
    if (method != 0 && method != 8) fail(422, "UNSUPPORTED_ZIP_COMPRESSION");
  }

  private static String readName(RandomAccessFile file, ByteBuffer entry) throws IOException {
    byte[] bytes = new byte[Short.toUnsignedInt(entry.getShort(28))];
    file.readFully(bytes);
    int flags = Short.toUnsignedInt(entry.getShort(8));
    return new String(
        bytes, (flags & 2048) != 0 ? StandardCharsets.UTF_8 : Charset.forName("IBM437"));
  }
}
