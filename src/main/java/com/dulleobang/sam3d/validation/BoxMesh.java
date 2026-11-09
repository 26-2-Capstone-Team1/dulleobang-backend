package com.dulleobang.sam3d.validation;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Conservative collision approximation, not an AI-reconstructed collision surface. */
public final class BoxMesh {
  private BoxMesh() {}

  public static byte[] create(JsonNode dims) {
    float w = (float) dims.path("width").asDouble() / 2,
        h = (float) dims.path("height").asDouble(),
        d = (float) dims.path("depth").asDouble() / 2;
    float[] vertices = {
      -w, 0, -d, w, 0, -d, w, h, -d, -w, h, -d, -w, 0, d, w, 0, d, w, h, d, -w, h, d
    };
    int[] indices = {
      0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 3, 7, 6, 3, 6, 2, 0, 4, 7, 0, 7, 3, 1,
      2, 6, 1, 6, 5
    };
    var binary = ByteBuffer.allocate(168).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : vertices) binary.putFloat(value);
    for (int value : indices) binary.putShort((short) value);
    return encode(document(w, h, d), binary.array());
  }

  private static ObjectNode document(float w, float h, float d) {
    var doc = (ObjectNode) StrictJson.parse("{}");
    doc.putObject("asset").put("version", "2.0");
    doc.put("scene", 0);
    doc.putArray("scenes").addObject().putArray("nodes").add(0);
    doc.putArray("nodes").addObject().put("mesh", 0).put("name", "collision-box");
    var primitive = doc.putArray("meshes").addObject().putArray("primitives").addObject();
    primitive.putObject("attributes").put("POSITION", 0);
    primitive.put("indices", 1);
    primitive.put("mode", 4);
    doc.putArray("buffers").addObject().put("byteLength", 168);
    var views = doc.putArray("bufferViews");
    views.addObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", 96);
    views.addObject().put("buffer", 0).put("byteOffset", 96).put("byteLength", 72);
    var accessors = doc.putArray("accessors");
    var position =
        accessors
            .addObject()
            .put("bufferView", 0)
            .put("componentType", 5126)
            .put("count", 8)
            .put("type", "VEC3");
    position.putArray("min").add(-w).add(0).add(-d);
    position.putArray("max").add(w).add(h).add(d);
    accessors
        .addObject()
        .put("bufferView", 1)
        .put("componentType", 5123)
        .put("count", 36)
        .put("type", "SCALAR");
    return doc;
  }

  private static byte[] encode(ObjectNode document, byte[] binary) {
    byte[] json = StrictJson.stringify(document).getBytes(StandardCharsets.UTF_8);
    int size = (json.length + 3) / 4 * 4;
    var result = ByteBuffer.allocate(28 + size + binary.length).order(ByteOrder.LITTLE_ENDIAN);
    result
        .putInt(0x46546c67)
        .putInt(2)
        .putInt(result.capacity())
        .putInt(size)
        .putInt(0x4e4f534a)
        .put(json);
    while (result.position() < 20 + size) result.put((byte) 32);
    return result.putInt(binary.length).putInt(0x004e4942).put(binary).array();
  }
}
