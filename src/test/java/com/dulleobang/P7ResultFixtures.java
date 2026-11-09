package com.dulleobang;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class P7ResultFixtures {
  static ObjectNode example(String schema) throws Exception {
    try (var stream = P7ResultFixtures.class.getResourceAsStream("/contract/v1/openapi.json")) {
      return (ObjectNode)
          StrictJson.parse(stream.readAllBytes())
              .path("components")
              .path("schemas")
              .path(schema)
              .path("examples")
              .get(0)
              .deepCopy();
    }
  }

  static byte[] glb(List<String> names) {
    var doc = JsonNodeFactory.instance.objectNode();
    doc.putObject("asset").put("version", "2.0");
    doc.put("scene", 0);
    var roots = doc.putArray("scenes").addObject().putArray("nodes");
    var nodes = doc.putArray("nodes");
    for (int n = 0; n < names.size(); n++) {
      roots.add(n);
      nodes.addObject().put("name", names.get(n)).put("mesh", 0);
    }
    doc.putArray("meshes")
        .addObject()
        .putArray("primitives")
        .addObject()
        .putObject("attributes")
        .put("POSITION", 0);
    var accessor = doc.putArray("accessors").addObject();
    accessor.put("bufferView", 0).put("componentType", 5126).put("count", 3).put("type", "VEC3");
    accessor.putArray("min").add(-.6).add(0).add(-.3);
    accessor.putArray("max").add(.6).add(.75).add(.3);
    doc.putArray("bufferViews").addObject().put("buffer", 0).put("byteLength", 36);
    doc.putArray("buffers").addObject().put("byteLength", 36);
    var binary = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
    for (float n : new float[] {-.6f, 0, -.3f, .6f, 0, -.3f, 0, .75f, .3f}) binary.putFloat(n);
    return glb(doc, binary.array());
  }

  static byte[] glb(JsonNode doc, byte[] binary) {
    byte[] json = StrictJson.stringify(doc).getBytes(StandardCharsets.UTF_8);
    int padded = (json.length + 3) / 4 * 4;
    var bytes =
        ByteBuffer.allocate(12 + 8 + padded + 8 + binary.length).order(ByteOrder.LITTLE_ENDIAN);
    bytes
        .putInt(0x46546c67)
        .putInt(2)
        .putInt(bytes.capacity())
        .putInt(padded)
        .putInt(0x4e4f534a)
        .put(json);
    while (bytes.position() < 20 + padded) bytes.put((byte) 32);
    bytes.putInt(binary.length).putInt(0x004e4942).put(binary);
    return bytes.array();
  }

  static ObjectNode glbDocument(byte[] raw) {
    var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    int n = b.getInt(12);
    return (ObjectNode) StrictJson.parse(Arrays.copyOfRange(raw, 20, 20 + n));
  }

  static byte[] binary(byte[] raw) {
    var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    return Arrays.copyOfRange(raw, 28 + b.getInt(12), raw.length);
  }

  static byte[] ply() {
    var header =
        new StringBuilder(
            "ply\n"
                + "format binary_little_endian 1.0\n"
                + "comment dulleobang_gs_ply_v1\n"
                + "element vertex 1\n");
    for (String name :
        "x y z f_dc_0 f_dc_1 f_dc_2 opacity scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3"
            .split(" ")) header.append("property float ").append(name).append('\n');
    header.append("end_header\n");
    byte[] h = header.toString().getBytes(StandardCharsets.UTF_8);
    var b = ByteBuffer.allocate(h.length + 56).order(ByteOrder.LITTLE_ENDIAN);
    b.put(h);
    for (int n = 0; n < 14; n++) b.putFloat(n == 10 ? 1 : 0);
    return b.array();
  }

  static List<String> names(JsonNode bundle) {
    var names = new ArrayList<String>();
    bundle.path("objects").forEach(o -> names.add(o.path("collisionNode").asText()));
    bundle
        .path("geometry")
        .path("structuralColliders")
        .forEach(o -> names.add(o.path("nodeName").asText()));
    bundle
        .path("geometry")
        .path("doors")
        .forEach(
            o -> {
              if (!o.path("sweepNode").isNull()) names.add(o.path("sweepNode").asText());
            });
    return names;
  }
}
