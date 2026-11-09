package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.*;
import java.util.*;

final class GlbAccessorReader {
  private static final Map<Integer, Integer> SIZES =
      Map.of(5120, 1, 5121, 1, 5122, 2, 5123, 2, 5125, 4, 5126, 4);
  private static final Map<String, Integer> TYPES =
      Map.of("SCALAR", 1, "VEC2", 2, "VEC3", 3, "VEC4", 4, "MAT2", 4, "MAT3", 9, "MAT4", 16);
  final JsonNode accessors;
  final JsonNode views;
  final ByteBuffer binary;

  GlbAccessorReader(GlbDocument document) {
    accessors = document.json().path("accessors");
    views = document.json().path("bufferViews");
    binary = document.binary();
    long bufferSize = integer(document.json().path("buffers").get(0).path("byteLength"));
    for (var view : views) {
      long offset = view.has("byteOffset") ? integer(view.get("byteOffset")) : 0,
          size = integer(view.path("byteLength"));
      require(
          (!view.has("buffer") || integer(view.get("buffer")) == 0)
              && offset >= 0
              && size > 0
              && offset + size <= bufferSize);
    }
    for (var a : accessors) {
      require(!a.has("sparse"));
      int count = integer(a.path("count")), component = integer(a.path("componentType"));
      require(
          count > 0 && SIZES.containsKey(component) && TYPES.containsKey(a.path("type").asText()));
      var view = views.get(index(a.path("bufferView"), views));
      int size = SIZES.get(component) * TYPES.get(a.path("type").asText()),
          stride = view.has("byteStride") ? integer(view.get("byteStride")) : size,
          offset = a.has("byteOffset") ? integer(a.get("byteOffset")) : 0;
      require(
          stride >= size
              && offset >= 0
              && (long) offset + (long) (count - 1) * stride + size
                  <= integer(view.path("byteLength")));
      if (component == 5126)
        for (int n = 0; n < count; n++)
          for (int c = 0; c < TYPES.get(a.path("type").asText()); c++)
            require(Float.isFinite(binary.getFloat(start(a) + (n * stride) + c * 4)));
    }
  }

  JsonNode get(JsonNode id) {
    return accessors.get(index(id, accessors));
  }

  int start(JsonNode a) {
    var view = views.get(index(a.path("bufferView"), views));
    return view.path("byteOffset").asInt() + a.path("byteOffset").asInt();
  }

  int stride(JsonNode a) {
    var view = views.get(index(a.path("bufferView"), views));
    return view.path("byteStride")
        .asInt(SIZES.get(integer(a.path("componentType"))) * TYPES.get(a.path("type").asText()));
  }

  void positions(JsonNode a, java.util.function.Consumer<double[]> consumer) {
    require(a.path("type").asText().equals("VEC3") && integer(a.path("componentType")) == 5126);
    int start = start(a), stride = stride(a), count = integer(a.path("count"));
    for (int n = 0; n < count; n++)
      consumer.accept(
          new double[] {
            binary.getFloat(start + n * stride),
            binary.getFloat(start + n * stride + 4),
            binary.getFloat(start + n * stride + 8)
          });
  }

  void positionBounds(JsonNode a) {
    require(
        a.path("min").isArray()
            && a.path("min").size() == 3
            && a.path("max").isArray()
            && a.path("max").size() == 3);
    double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
        max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    positions(
        a,
        p -> {
          for (int n = 0; n < 3; n++) {
            min[n] = Math.min(min[n], p[n]);
            max[n] = Math.max(max[n], p[n]);
          }
        });
    for (int n = 0; n < 3; n++)
      require(
          a.path("min").get(n).isNumber()
              && a.path("max").get(n).isNumber()
              && Math.abs(a.path("min").get(n).asDouble() - min[n]) <= .001
              && Math.abs(a.path("max").get(n).asDouble() - max[n]) <= .001);
  }

  void indices(JsonNode a, int vertexCount) {
    int type = integer(a.path("componentType"));
    require(a.path("type").asText().equals("SCALAR") && Set.of(5121, 5123, 5125).contains(type));
    for (int n = 0; n < integer(a.path("count")); n++) {
      int p = start(a) + n * stride(a);
      long value =
          type == 5121
              ? Byte.toUnsignedInt(binary.get(p))
              : type == 5123
                  ? Short.toUnsignedInt(binary.getShort(p))
                  : Integer.toUnsignedLong(binary.getInt(p));
      require(value < vertexCount);
    }
  }
}
