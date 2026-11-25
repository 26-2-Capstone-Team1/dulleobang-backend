package com.dulleobang.listing.repository;

import com.dulleobang.common.json.StrictJson;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

record ListingSearchSql(String suffix, MapSqlParameterSource parameters) {
  private static final Map<String, String> RANGES =
      Map.of(
          "Deposit",
          "deposit",
          "MonthlyRent",
          "monthly_rent",
          "AreaM2",
          "exclusive_area",
          "Lat",
          "latitude",
          "Lng",
          "longitude");

  static ListingSearchSql build(UUID owner, Map<String, Object> q) {
    var conditions = new ArrayList<>(List.of("deleted_at IS NULL"));
    var params = new MapSqlParameterSource();
    String sort = "LATEST";
    if (owner == null) {
      conditions.add("status = 'PUBLISHED'");
      publicFilters(q, conditions, params);
      sort = q.getOrDefault("sort", "LATEST").toString();
    } else {
      conditions.add("owner_id = :owner");
      params.addValue("owner", owner);
      equality(q, "status", "status", conditions, params);
    }
    String order =
        switch (sort) {
          case "DEPOSIT_ASC" -> "deposit ASC";
          case "MONTHLY_RENT_ASC" -> "monthly_rent ASC";
          case "AREA_DESC" -> "exclusive_area DESC";
          // The old comparator used timestamps serialized at millisecond precision.
          default -> "date_trunc('milliseconds', created_at, 'UTC') DESC";
        };
    return new ListingSearchSql(
        " WHERE " + String.join(" AND ", conditions) + " ORDER BY " + order + ", listing_id",
        params);
  }

  private static void publicFilters(
      Map<String, Object> q, List<String> conditions, MapSqlParameterSource params) {
    var words = q.getOrDefault("q", "").toString().strip().toLowerCase(Locale.ROOT);
    if (!words.isEmpty()) {
      int count = 0;
      for (String word : words.split("\\s+")) {
        var name = "word" + count++;
        conditions.add("strpos(lower(road_address), :" + name + ") > 0");
        params.addValue(name, word);
      }
    }
    equality(q, "regionCode", "region_code", conditions, params);
    equality(q, "leaseType", "lease_type", conditions, params);
    if (q.containsKey("hasSpace"))
      conditions.add(
          Boolean.TRUE.equals(q.get("hasSpace"))
              ? "current_space_version IS NOT NULL"
              : "current_space_version IS NULL");
    if (q.get("roomType") instanceof List<?> types) {
      conditions.add(types.isEmpty() ? "FALSE" : "room_type IN (:roomType)");
      params.addValue("roomType", types);
    }
    RANGES.forEach(
        (key, column) -> {
          bound(q, "min" + key, column, ">=", conditions, params);
          bound(q, "max" + key, column, "<=", conditions, params);
        });
    if (q.get("rentalOptions") instanceof List<?> options && !options.isEmpty()) {
      conditions.add(
          "rental_options @> ARRAY(SELECT jsonb_array_elements_text(CAST(:options AS jsonb)))");
      params.addValue("options", StrictJson.stringify(options));
    }
  }

  private static void equality(
      Map<String, Object> q,
      String key,
      String column,
      List<String> conditions,
      MapSqlParameterSource params) {
    bound(q, key, column, "=", conditions, params);
  }

  private static void bound(
      Map<String, Object> q,
      String key,
      String column,
      String operator,
      List<String> conditions,
      MapSqlParameterSource params) {
    if (q.containsKey(key)) {
      conditions.add(column + " " + operator + " :" + key);
      params.addValue(key, q.get(key));
    }
  }
}
