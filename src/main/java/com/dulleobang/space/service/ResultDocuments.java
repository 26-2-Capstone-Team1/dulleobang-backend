package com.dulleobang.space.service;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

final class ResultDocuments {
  private ResultDocuments() {}

  static JsonNode reference(String type, UUID id, int version) {
    return StrictJson.parse(
        StrictJson.stringify(Map.of("resourceType", type, "resourceId", id, "version", version)));
  }

  static void clean(JsonNode node) {
    if (node.isObject() && node.has("fileId") && node.has("sha256")) {
      ((ObjectNode) node).remove(List.of("url", "urlExpiresAt"));
    }
    for (var child : node) clean(child);
  }
}
