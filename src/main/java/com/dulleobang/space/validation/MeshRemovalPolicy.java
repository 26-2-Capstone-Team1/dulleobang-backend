package com.dulleobang.space.validation;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class MeshRemovalPolicy {
  private MeshRemovalPolicy() {}

  static void validate(JsonNode source, JsonNode result, Set<String> removed) {
    if (!source.has("meshSource")) return;
    var before = source.path("meshSource");
    var after = result.path("meshSource");
    require(sameAsset(before.path("roomAsset"), after.path("roomAsset")));
    require(sameAsset(before.path("roomCollisionAsset"), after.path("roomCollisionAsset")));
    require(before.path("roomGeometry").equals(after.path("roomGeometry")));
    require(before.path("glass").equals(after.path("glass")));
    var expected = new HashMap<String, JsonNode>();
    for (var entry : before.path("objectAssets"))
      if (!removed.contains(entry.path("objectId").asText()))
        expected.put(entry.path("objectId").asText(), entry.path("asset"));
    var seen = new HashSet<String>();
    for (var entry : after.path("objectAssets")) {
      String id = entry.path("objectId").asText();
      require(seen.add(id) && expected.containsKey(id));
      require(sameAsset(expected.get(id), entry.path("asset")));
    }
    require(seen.equals(expected.keySet()));
  }

  private static boolean sameAsset(JsonNode before, JsonNode after) {
    if (!before.isObject() || !after.isObject()) return false;
    return List.of("sha256", "byteSize", "format", "contentType").stream()
        .allMatch(key -> before.path(key).equals(after.path(key)));
  }

  private static void require(boolean condition) {
    if (!condition) throw new ApiException(422, "REMOVAL_MESH_MISMATCH");
  }
}
