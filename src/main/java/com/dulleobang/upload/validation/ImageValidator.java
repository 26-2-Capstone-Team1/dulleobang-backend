package com.dulleobang.upload.validation;

import com.dulleobang.common.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import javax.imageio.*;
import org.springframework.stereotype.Component;

@Component
public class ImageValidator {
  public record Size(int width, int height) {}

  public Size validate(byte[] raw, String mime) {
    var image = decode(raw, mime, false, false);
    return new Size(image.getWidth(), image.getHeight());
  }

  public Size depth(byte[] raw, boolean confidence) {
    var image = decode(raw, "image/png", true, confidence);
    return new Size(image.getWidth(), image.getHeight());
  }

  private BufferedImage decode(byte[] raw, String mime, boolean depth, boolean confidence) {
    String error = depth ? "INVALID_DEPTH_ENCODING" : "INVALID_IMAGE";
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new ApiException(422, error);
      var reader = readers.next();
      try {
        reader.setInput(input, false, true);
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase(mime.equals("image/png") ? "PNG" : "JPEG"))
          throw new ApiException(422, "CONTENT_TYPE_MISMATCH");
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (depth) {
          if (raw.length < 26
              || raw[24] != (confidence ? 8 : 16)
              || raw[25] != 0
              || w < 1
              || h < 1
              || w > 4096
              || h > 4096) throw new ApiException(422, error);
        } else if (w < 640 || h < 480 || w > 8192 || h > 8192 || (long) w * h > 40000000)
          throw new ApiException(422, "INVALID_IMAGE_DIMENSIONS");
        if (format.equalsIgnoreCase("PNG")) checkPng(raw);
        reader.addIIOReadWarningListener(
            (source, warning) -> {
              throw new ApiException(422, error);
            });
        if (reader.getNumImages(true) != 1) throw new ApiException(422, error);
        var image = reader.read(0);
        if (confidence) {
          var raster = image.getRaster();
          for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
              if (raster.getSample(x, y, 0) > 2)
                throw new ApiException(422, "INVALID_CONFIDENCE_VALUES");
        }
        return image;
      } finally {
        reader.dispose();
      }
    } catch (ApiException ex) {
      throw ex;
    } catch (IOException | RuntimeException ex) {
      throw new ApiException(422, error);
    }
  }

  private void checkPng(byte[] raw) {
    int p = 8;
    while (p + 12 <= raw.length) {
      int size = ByteBuffer.wrap(raw, p, 4).getInt();
      if (size < 0 || (long) p + 12 + size > raw.length)
        throw new ApiException(422, "INVALID_IMAGE");
      if (raw[p + 4] == 'a' && raw[p + 5] == 'c' && raw[p + 6] == 'T' && raw[p + 7] == 'L')
        throw new ApiException(422, "INVALID_IMAGE");
      p += 12 + size;
    }
  }

  public BufferedImage decode(byte[] raw, String mime) {
    return decode(raw, mime, false, false);
  }
}
