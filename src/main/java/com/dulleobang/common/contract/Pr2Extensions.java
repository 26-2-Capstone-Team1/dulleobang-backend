package com.dulleobang.common.contract;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.io.ClassPathResource;

/** Additive wire extensions; the frozen v1.1 source contract remains available unchanged. */
final class Pr2Extensions {
  private Pr2Extensions() {}

  static void apply(JsonNode document) throws java.io.IOException {
    var schemas = (ObjectNode) document.path("components").path("schemas");
    try (var in = new ClassPathResource("contract/extensions/pr2.json").getInputStream()) {
      var added = StrictJson.parse(in.readAllBytes()).path("components").path("schemas");
      added.fields().forEachRemaining(entry -> schemas.set(entry.getKey(), entry.getValue()));
    }
    append(schemas, "AssetRef", "format", "POINT_CLOUD_PLY_V1");
    append(schemas, "ArtifactInput", "format", "POINT_CLOUD_PLY_V1");
    append(schemas, "ArtifactInput", "role", "SOURCE_MESH");
    ((ObjectNode) schemas.path("ArtifactCreate").path("properties").path("files"))
        .put("maxItems", 505);
    ((ObjectNode) schemas.path("SpaceBundle").path("properties"))
        .putObject("meshSource")
        .put("$ref", "#/components/schemas/Pr2MeshSource");
    ((ObjectNode) schemas.path("RemovalInput").path("properties"))
        .putObject("meshDownloads")
        .put("type", "array")
        .put("maxItems", 502)
        .putObject("items")
        .put("$ref", "#/components/schemas/DownloadGrant");
  }

  private static void append(ObjectNode schemas, String name, String field, String value) {
    var values = (ArrayNode) schemas.path(name).path("properties").path(field).path("enum");
    values.add(value);
  }
}
