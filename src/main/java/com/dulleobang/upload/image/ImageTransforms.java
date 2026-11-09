package com.dulleobang.upload.image;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class ImageTransforms {
  private ImageTransforms() {}

  static BufferedImage orient(BufferedImage image, int orientation) {
    int w = image.getWidth(), h = image.getHeight();
    boolean swap = orientation >= 5;
    var oriented = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int nx = x, ny = y;
        switch (orientation) {
          case 2 -> nx = w - 1 - x;
          case 3 -> {
            nx = w - 1 - x;
            ny = h - 1 - y;
          }
          case 4 -> ny = h - 1 - y;
          case 5 -> {
            nx = y;
            ny = x;
          }
          case 6 -> {
            nx = h - 1 - y;
            ny = x;
          }
          case 7 -> {
            nx = h - 1 - y;
            ny = w - 1 - x;
          }
          case 8 -> {
            nx = y;
            ny = w - 1 - x;
          }
          default -> {}
        }
        oriented.setRGB(nx, ny, image.getRGB(x, y));
      }
    return oriented;
  }

  static BufferedImage resize(BufferedImage oriented) {
    double scale = Math.min(1, 2048.0 / Math.max(oriented.getWidth(), oriented.getHeight()));
    var clean =
        new BufferedImage(
            Math.max(1, (int) Math.round(oriented.getWidth() * scale)),
            Math.max(1, (int) Math.round(oriented.getHeight() * scale)),
            BufferedImage.TYPE_INT_RGB);
    var g = clean.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.drawImage(oriented, 0, 0, clean.getWidth(), clean.getHeight(), null);
    } finally {
      g.dispose();
    }
    return clean;
  }
}
