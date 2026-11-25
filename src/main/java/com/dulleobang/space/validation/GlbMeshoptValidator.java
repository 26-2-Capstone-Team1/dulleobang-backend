package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;
import static com.dulleobang.space.validation.GlbExtensions.*;

import com.fasterxml.jackson.databind.JsonNode;

final class GlbMeshoptValidator {
  private GlbMeshoptValidator() {}

  static void buffers(JsonNode doc) {
    var buffers = doc.path("buffers");
    if (buffers.size() == 1) return;
    require(declared(doc, "extensionsRequired", MESHOPT));
    long decodedBytes = 0;
    for (int n = 1; n < buffers.size(); n++) {
      var buffer = buffers.get(n);
      var fallback = extension(buffer, MESHOPT);
      int size = integer(buffer.path("byteLength"));
      require(!buffer.has("uri") && size > 0);
      require(fallback.isMissingNode() || (fallback.size() == 1 && fallback.path("fallback").isBoolean()
          && fallback.path("fallback").booleanValue()));
      decodedBytes += size;
      require(decodedBytes <= 256L * 1024 * 1024);
    }
  }

  static boolean compressed(JsonNode view) {
    return !extension(view, MESHOPT).isMissingNode();
  }

  static int buffer(JsonNode view) {
    return view.has("buffer") ? integer(view.get("buffer")) : 0;
  }

  static void view(JsonNode view, JsonNode buffers, GlbDocument document) {
    int index = buffer(view);
    long offset = view.has("byteOffset") ? integer(view.get("byteOffset")) : 0;
    long size = integer(view.path("byteLength"));
    require(index >= 0 && index < buffers.size() && offset >= 0 && size > 0
        && offset + size <= integer(buffers.get(index).path("byteLength")));
    if (compressed(view)) compressedView(view, size, integer(buffers.get(0).path("byteLength")), document);
    else require(index == 0);
  }

  private static void compressedView(JsonNode view, long size, int binarySize, GlbDocument document) {
    var ext = extension(view, MESHOPT);
    int offset = ext.has("byteOffset") ? integer(ext.get("byteOffset")) : 0;
    int length = integer(ext.path("byteLength"));
    int stride = integer(ext.path("byteStride"));
    int count = integer(ext.path("count"));
    require(integer(ext.path("buffer")) == 0 && offset >= 0 && length > 0
        && (long) offset + length <= binarySize && count > 0 && stride > 0
        && (long) count * stride == size);
    require(!view.has("byteStride") || integer(view.get("byteStride")) == stride);
    String mode = ext.path("mode").asText();
    String filter = ext.path("filter").asText("NONE");
    int header = Byte.toUnsignedInt(document.binary().get(offset));
    switch (mode) {
      case "ATTRIBUTES" -> {
        require(stride <= 256 && stride % 4 == 0 && (header == 0xa0 || header == 0xa1));
        require(switch (filter) {
          case "NONE", "EXPONENTIAL" -> true;
          case "OCTAHEDRAL" -> stride == 4 || stride == 8;
          case "QUATERNION" -> stride == 8;
          default -> false;
        });
      }
      case "TRIANGLES" -> require(count % 3 == 0 && (stride == 2 || stride == 4)
          && filter.equals("NONE") && (header == 0xe0 || header == 0xe1));
      case "INDICES" -> require((stride == 2 || stride == 4) && filter.equals("NONE")
          && (header == 0xd0 || header == 0xd1));
      default -> require(false);
    }
  }
}
