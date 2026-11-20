package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.require;

import java.nio.ByteBuffer;
import java.util.HexFormat;

final class GlbKtx2Validator {
  private static final byte[] IDENTIFIER = HexFormat.of().parseHex("ab4b5458203230bb0d0a1a0a");
  private static final int HEADER_BYTES = 80;
  private static final int LEVEL_BYTES = 24;

  private GlbKtx2Validator() {}

  static void validate(ByteBuffer image, boolean room) {
    require(image.capacity() >= HEADER_BYTES + LEVEL_BYTES);
    for (int i = 0; i < IDENTIFIER.length; i++) require(image.get(i) == IDENTIFIER[i]);
    int width = image.getInt(20), height = image.getInt(24);
    int levels = image.getInt(40), scheme = image.getInt(44);
    require(image.getInt(12) == 0 && image.getInt(16) == 1
        && GlbTextureValidator.allowedSize(width, height, room)
        && image.getInt(28) == 0 && image.getInt(32) == 0 && image.getInt(36) == 1
        && levels >= 0 && levels <= 32 - Integer.numberOfLeadingZeros(Math.max(width, height))
        && scheme >= 0 && scheme <= 2);
    int levelCount = Math.max(1, levels);
    int indexEnd = HEADER_BYTES + levelCount * LEVEL_BYTES;
    require(indexEnd <= image.capacity() && image.getInt(52) > 0);
    range(image, image.getInt(48), image.getInt(52), indexEnd);
    range(image, image.getInt(56), image.getInt(60), indexEnd);
    range(image, image.getLong(64), image.getLong(72), indexEnd);
    for (int i = 0; i < levelCount; i++) {
      int offset = HEADER_BYTES + i * LEVEL_BYTES;
      long length = image.getLong(offset + 8);
      require(length > 0 && image.getLong(offset + 16) >= 0);
      range(image, image.getLong(offset), length, indexEnd);
    }
  }

  private static void range(ByteBuffer image, long offset, long length, int indexEnd) {
    require(length == 0 ? offset == 0 : offset >= indexEnd && length > 0
        && offset <= image.capacity() && length <= image.capacity() - offset);
  }
}
