package com.dulleobang.catalog.domain;

import com.dulleobang.common.json.ApiTime;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CatalogItem(
    UUID itemId,
    String catalogId,
    String name,
    String category,
    JsonNode dimensions,
    JsonNode boundingBox,
    JsonNode quality,
    String sourceUrl,
    Instant importedAt) {
  public static final String ID_PREFIX = "ikea-";

  public String article() {
    return catalogId.substring(ID_PREFIX.length());
  }

  public Map<String, Object> model(
      Map<String, Object> modelAsset, Map<String, Object> collisionAsset, Instant createdAt) {
    return Map.of(
        "modelVersion", 1,
        "modelAsset", modelAsset,
        "collisionAsset", collisionAsset,
        "dimensions", dimensions,
        "boundingBox", boundingBox,
        "quality", quality,
        "createdAt", ApiTime.format(createdAt));
  }
}
