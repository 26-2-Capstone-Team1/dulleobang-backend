package com.dulleobang.furniture.mapper;

import com.dulleobang.common.json.*;
import com.dulleobang.furniture.domain.FurnitureItem;
import java.util.*;

public final class FurnitureMapper {
  private FurnitureMapper() {}

  public static Map<String, Object> response(FurnitureItem item, boolean mobile) {
    var result = new LinkedHashMap<String, Object>();
    result.put("furnitureId", item.id());
    result.put("name", item.name());
    result.put(
        "category", mobile ? FurnitureCategoryMapper.mobile(item.category()) : item.category());
    result.put("status", mobile && item.status().equals("READY") ? "COMPLETED" : item.status());
    var model = item.model();
    if (mobile) {
      result.put(
          "modelUri", model == null ? null : model.path("modelAsset").path("url").asText(null));
      result.put("dimensions", model == null ? null : model.get("dimensions"));
      result.put("boundingBox", model == null ? null : model.get("boundingBox"));
      result.put(
          "collisionMeshUri",
          model == null ? null : model.path("collisionAsset").path("url").asText(null));
    } else {
      result.put("ownerId", item.owner());
      result.put("measuredDimensions", item.measuredDimensions());
      result.put("version", item.version());
      result.put("model", model);
      result.put("activeJobId", item.activeJob());
      result.put("createdAt", ApiTime.format(item.createdAt()));
      result.put("updatedAt", ApiTime.format(item.updatedAt()));
    }
    return result;
  }
}
