package com.dulleobang.space.validation;

import static com.dulleobang.space.validation.GlbContainerReader.require;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class GlbExtensions {
  static final String BASIS = "KHR_texture_basisu";
  static final String MESHOPT = "EXT_meshopt_compression";
  private static final String UNLIT = "KHR_materials_unlit";

  private GlbExtensions() {}

  static boolean declared(JsonNode doc, String key, String name) {
    for (var value : doc.path(key)) if (name.equals(value.asText())) return true;
    return false;
  }

  static JsonNode extension(JsonNode node, String name) {
    return node.path("extensions").path(name);
  }

  static void validate(JsonNode doc, boolean collision, boolean room) {
    var allowed = new IdentityHashMap<JsonNode, String>();
    if (!collision) doc.path("materials").forEach(node -> allowed.put(node, UNLIT));
    if (room) {
      doc.path("textures").forEach(node -> allowed.put(node, BASIS));
      doc.path("buffers").forEach(node -> allowed.put(node, MESHOPT));
      doc.path("bufferViews").forEach(node -> allowed.put(node, MESHOPT));
    }
    for (String key : List.of("extensionsUsed", "extensionsRequired")) {
      var values = doc.path(key);
      require(values.isMissingNode() || values.isArray());
      for (var value : values) {
        String name = value.asText();
        require(value.isTextual() && ((!collision && name.equals(UNLIT))
            || (room && (name.equals(BASIS) || name.equals(MESHOPT)))));
        if (!name.equals(UNLIT) && key.equals("extensionsRequired"))
          require(declared(doc, "extensionsUsed", name));
      }
    }
    validateLocations(doc, allowed, doc);
  }

  private static void validateLocations(JsonNode value, Map<JsonNode, String> allowed, JsonNode doc) {
    if (value.isObject()) {
      if (value.has("extensions") && !value.path("extensions").isEmpty()) {
        var extensions = value.path("extensions");
        String name = allowed.get(value);
        require(name != null && extensions.isObject() && extensions.size() == 1);
        var extension = extensions.path(name);
        require(extension.isObject());
        require(name.equals(UNLIT) ? extension.isEmpty() : declared(doc, "extensionsUsed", name));
      }
      value.forEach(child -> validateLocations(child, allowed, doc));
    } else if (value.isArray()) value.forEach(child -> validateLocations(child, allowed, doc));
  }
}
