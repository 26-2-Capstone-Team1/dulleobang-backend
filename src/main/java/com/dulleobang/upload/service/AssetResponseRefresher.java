package com.dulleobang.upload.service;

import com.dulleobang.common.idempotency.StoredResponse;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssetResponseRefresher {
  private static final Set<String> URL_FIELDS =
      Set.of("url", "imageUris", "thumbnailUri", "modelUri", "collisionMeshUri");
  private final AssetPresentationService assets;

  public AssetResponseRefresher(AssetPresentationService assets) {
    this.assets = assets;
  }

  /** The idempotent operation must have rechecked its containing resource's access first. */
  public StoredResponse refresh(StoredResponse response) {
    var value = StrictJson.parse(response.body());
    refresh(value, "");
    return new StoredResponse(response.status(), StrictJson.stringify(value), response.headers());
  }

  private void refresh(JsonNode node, String field) {
    if (node instanceof ObjectNode object) {
      if (node.has("fileId") && node.has("sha256")) {
        var grant = assets.present(UUID.fromString(node.path("fileId").asText()));
        object.put("url", grant.get("url").toString());
        object.put("urlExpiresAt", grant.get("urlExpiresAt").toString());
        return;
      }
      var fields = new ArrayList<String>();
      object.fieldNames().forEachRemaining(fields::add);
      for (String key : fields) {
        var child = object.get(key);
        if (child.isTextual() && URL_FIELDS.contains(key)) object.put(key, url(child.asText()));
        else refresh(child, key);
      }
    } else if (node instanceof ArrayNode array) {
      for (int n = 0; n < array.size(); n++) {
        var child = array.get(n);
        if (child.isTextual() && URL_FIELDS.contains(field))
          array.set(n, TextNode.valueOf(url(child.asText())));
        else refresh(child, field);
      }
    }
  }

  private String url(String url) {
    String path;
    try {
      path = URI.create(url).getPath();
    } catch (IllegalArgumentException ex) {
      return url;
    }
    if (path == null || !path.matches("/storage/[0-9a-fA-F-]{36}")) return url;
    return assets.present(UUID.fromString(path.substring(9))).get("url").toString();
  }
}
