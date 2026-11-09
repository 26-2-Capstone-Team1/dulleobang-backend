package com.dulleobang.compatibility;

import com.dulleobang.common.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

public final class ImageProbe {
  private ImageProbe() {}

  public static BufferedImage decode(byte[] raw) {
    try {
      var image = ImageIO.read(new ByteArrayInputStream(raw));
      if (image == null || image.getWidth() < 1 || image.getHeight() < 1) throw new IOException();
      return image;
    } catch (IOException ex) {
      throw new ApiException(422, "INVALID_IMAGE");
    }
  }

  public static int[] pngSamples(byte[] raw, int bits, boolean confidence) {
    if (raw.length < 26
        || raw[0] != (byte) 137
        || raw[1] != 80
        || raw[2] != 78
        || raw[3] != 71
        || (raw[24] & 255) != bits
        || raw[25] != 0) throw new ApiException(422, "INVALID_DEPTH_IMAGE");
    var image = decode(raw);
    var raster = image.getRaster();
    int[] samples = raster.getSamples(0, 0, image.getWidth(), image.getHeight(), 0, (int[]) null);
    if (confidence)
      for (int sample : samples) if (sample > 2) throw new ApiException(422, "INVALID_CONFIDENCE");
    return samples;
  }

  public static byte[] sanitizedJpeg(byte[] raw) {
    BufferedImage input = decode(raw);
    BufferedImage clean =
        new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_RGB);
    var graphics = clean.createGraphics();
    try {
      graphics.drawImage(input, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    try (var out = new ByteArrayOutputStream()) {
      if (!ImageIO.write(clean, "JPEG", out)) throw new IOException();
      return out.toByteArray();
    } catch (IOException ex) {
      throw new ApiException(422, "INVALID_IMAGE");
    }
  }
}
