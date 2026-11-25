package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;
import static com.dulleobang.space.validation.GlbExtensions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Set;
import javax.imageio.ImageIO;

final class GlbTextureValidator {
  private static final long MAX_TEXTURE_PIXELS = 40_000_000;
  private static final int MAX_ROOM_TEXTURE_SIDE = 8192;

  private GlbTextureValidator() {}

  static boolean allowedSize(int width, int height, boolean room) {
    return width > 0 && height > 0
        && ((long) width * height <= MAX_TEXTURE_PIXELS
            || (room && width <= MAX_ROOM_TEXTURE_SIDE && height <= MAX_ROOM_TEXTURE_SIDE));
  }

  private static boolean isKtx2(com.fasterxml.jackson.databind.JsonNode image) {
    return image.path("mimeType").asText().equals("image/ktx2");
  }

  static void validate(GlbDocument file, GlbAccessorReader accessors, boolean room)
      throws IOException {
    var doc = file.json();
    boolean basis = declared(doc, "extensionsUsed", BASIS);
    var images = doc.path("images");
    if (basis) {
      for (var texture : doc.path("textures")) {
        if (texture.has("source"))
          require(!isKtx2(images.get(index(texture.path("source"), images))));
        var ext = extension(texture, BASIS);
        if (!ext.isMissingNode()) {
          require(isKtx2(images.get(index(ext.path("source"), images))));
          require(texture.has("source") || declared(doc, "extensionsRequired", BASIS));
        }
      }
    }
    for (var image : images) {
      boolean ktx2 = isKtx2(image);
      require(!image.has("uri") && (ktx2 ? basis
          : Set.of("image/png", "image/jpeg").contains(image.path("mimeType").asText())));
      var view = accessors.views.get(index(image.path("bufferView"), accessors.views));
      require(GlbMeshoptValidator.buffer(view) == 0 && !GlbMeshoptValidator.compressed(view));
      int start = view.path("byteOffset").asInt(), size = integer(view.path("byteLength"));
      if (ktx2) {
        GlbKtx2Validator.validate(file.binary().slice(start, size).order(ByteOrder.LITTLE_ENDIAN), room);
        continue;
      }
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
          require(allowedSize(w, h, room));
          require(reader.read(0) != null);
        } finally {
          reader.dispose();
        }
      }
    }
  }
}
