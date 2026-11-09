package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import javax.imageio.ImageIO;

final class GlbTextureValidator {
  private GlbTextureValidator() {}

  static void validate(GlbDocument file, GlbAccessorReader accessors) throws IOException {
    var doc = file.json();
    for (var image : doc.path("images")) {
      require(
          !image.has("uri")
              && Set.of("image/png", "image/jpeg").contains(image.path("mimeType").asText()));
      var view = accessors.views.get(index(image.path("bufferView"), accessors.views));
      int start = view.path("byteOffset").asInt(), size = integer(view.path("byteLength"));
      byte[] bytes = new byte[size];
      file.binary().duplicate().position(start).get(bytes);
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        require(readers.hasNext());
        var reader = readers.next();
        try {
          reader.setInput(input);
          require(
              reader
                  .getFormatName()
                  .equalsIgnoreCase(
                      image.path("mimeType").asText().equals("image/png") ? "PNG" : "JPEG"));
          int w = reader.getWidth(0), h = reader.getHeight(0);
          require(w > 0 && h > 0 && (long) w * h <= 40000000);
          require(reader.read(0) != null);
        } finally {
          reader.dispose();
        }
      }
    }
  }
}
