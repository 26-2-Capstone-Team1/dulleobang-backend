package com.dulleobang.furniture.mapper;

import java.util.Locale;
import java.util.Map;

public final class FurnitureCategoryMapper {
  private static final Map<String, String> APP =
      Map.of(
          "bed",
          "BED",
          "desk",
          "DESK",
          "sofa",
          "SOFA",
          "closet",
          "WARDROBE",
          "storage",
          "SHELF",
          "chair",
          "CHAIR",
          "other",
          "OTHER");

  private FurnitureCategoryMapper() {}

  public static String canonical(String category) {
    return APP.getOrDefault(category, category);
  }

  public static String mobile(String category) {
    return APP.entrySet().stream()
        .filter(e -> e.getValue().equals(category))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(category.toLowerCase(Locale.ROOT));
  }
}
