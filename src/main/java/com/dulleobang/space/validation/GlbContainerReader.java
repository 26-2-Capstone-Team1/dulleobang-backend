package com.dulleobang.space.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.*;

final class GlbContainerReader {
  private GlbContainerReader() {}

  static GlbDocument read(byte[] raw, boolean collision, boolean room) {
    var file = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    require(
        raw.length >= 20
            && file.getInt() == 0x46546c67
            && file.getInt() == 2
            && file.getInt() == raw.length);
    int length = file.getInt();
    require(
        length > 0 && length % 4 == 0 && file.getInt() == 0x4e4f534a && length <= file.remaining());
    byte[] json = new byte[length];
    file.get(json);
    int end = json.length;
    while (end > 0 && (json[end - 1] == 0 || json[end - 1] == 32)) end--;
    var doc = StrictJson.parse(java.util.Arrays.copyOf(json, end));
    ByteBuffer binary = ByteBuffer.allocate(0);
    if (file.hasRemaining()) {
      require(file.remaining() >= 8);
      int size = file.getInt();
      require(
          size >= 0 && size % 4 == 0 && file.getInt() == 0x004e4942 && size == file.remaining());
      binary = file.slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    require(doc.isObject() && doc.path("asset").path("version").asText().equals("2.0"));
    for (String key : new String[] {"animations", "skins"})
      require(doc.path(key).isMissingNode() || doc.path(key).isEmpty());
    GlbExtensions.validate(doc, collision, room);
    if (collision)
      for (String key : new String[] {"images", "textures", "samplers"})
        require(doc.path(key).isMissingNode() || doc.path(key).isEmpty());
    var buffers = doc.path("buffers");
    require(buffers.isArray() && !buffers.isEmpty() && !buffers.get(0).has("uri"));
    GlbMeshoptValidator.buffers(doc);
    long size = integer(buffers.get(0).path("byteLength"));
    require(size > 0 && size <= binary.remaining() && binary.remaining() <= size + 3);
    return new GlbDocument(doc, binary);
  }

  static int integer(JsonNode node) {
    require(node.isIntegralNumber() && node.canConvertToInt());
    return node.asInt();
  }

  static int index(JsonNode node, JsonNode values) {
    int index = integer(node);
    require(index >= 0 && index < values.size());
    return index;
  }

  static void require(boolean condition) {
    if (!condition) throw new ApiException(422, "INVALID_ARTIFACT");
  }
}
