package com.dulleobang.space.service;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.service.AssetPresentationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BundlePresentationService {
  private final AssetPresentationService assets;

  public BundlePresentationService(AssetPresentationService assets) {
    this.assets = assets;
  }

  /** The caller must first authorize access to the containing space or model. */
  public JsonNode present(JsonNode value) {
    JsonNode copy = value.deepCopy();
    refresh(copy);
    return copy;
  }

  private void refresh(JsonNode node) {
    if (node.isObject() && node.has("fileId") && node.has("sha256")) {
      var grant =
          StrictJson.parse(
              StrictJson.stringify(assets.present(UUID.fromString(node.path("fileId").asText()))));
      ((ObjectNode) node).set("url", grant.get("url"));
      ((ObjectNode) node).set("urlExpiresAt", grant.get("urlExpiresAt"));
      return;
    }
    for (JsonNode child : node) refresh(child);
  }
}
