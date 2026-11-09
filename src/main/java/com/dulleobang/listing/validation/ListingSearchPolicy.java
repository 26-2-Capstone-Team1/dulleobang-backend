package com.dulleobang.listing.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.listing.domain.ListingRecord;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ListingSearchPolicy {
  public void validate(Map<String, Object> q) {
    long bounds =
        List.of("minLat", "maxLat", "minLng", "maxLng").stream().filter(q::containsKey).count();
    if (bounds != 0 && bounds != 4) fail(400, "INCOMPLETE_MAP_BOUNDS");
    if (bounds != 4 && ((Number) q.getOrDefault("limit", 20)).intValue() > 50)
      fail(400, "INVALID_QUERY_PARAMETER");
    for (String key : List.of("Deposit", "MonthlyRent", "AreaM2", "Lat", "Lng"))
      if (q.containsKey("min" + key)
          && q.containsKey("max" + key)
          && number(q.get("min" + key)) > number(q.get("max" + key))) fail(422, "INVALID_RANGE");
    if (q.getOrDefault("leaseType", "").equals("JEONSE")
        && number(q.getOrDefault("minMonthlyRent", 0)) > 0) fail(422, "INVALID_PRICE_FILTER");
  }

  public boolean matches(ListingRecord listing, Map<String, Object> q) {
    var v = listing.fields();
    var location = v.path("location");
    var price = v.path("price");
    for (String word :
        q.getOrDefault("q", "").toString().strip().toLowerCase(Locale.ROOT).split("\\s+"))
      if (!location.path("roadAddress").asText().toLowerCase(Locale.ROOT).contains(word))
        return false;
    for (var e :
        Map.of(
                "regionCode",
                location.path("regionCode").asText(),
                "leaseType",
                price.path("leaseType").asText(),
                "hasSpace",
                !v.path("currentSpaceVersion").isNull())
            .entrySet())
      if (q.containsKey(e.getKey()) && !q.get(e.getKey()).equals(e.getValue())) return false;
    if (q.containsKey("roomType")
        && !((List<?>) q.get("roomType")).contains(v.path("roomType").asText())) return false;
    for (var e :
        Map.of(
                "Deposit",
                price.path("deposit").asDouble(),
                "MonthlyRent",
                price.path("monthlyRent").asDouble(),
                "AreaM2",
                v.path("exclusiveAreaM2").asDouble(),
                "Lat",
                location.path("latitude").asDouble(),
                "Lng",
                location.path("longitude").asDouble())
            .entrySet()) {
      if (number(q.getOrDefault("min" + e.getKey(), -Double.MAX_VALUE)) > e.getValue()
          || number(q.getOrDefault("max" + e.getKey(), Double.MAX_VALUE)) < e.getValue())
        return false;
    }
    Set<String> options = new HashSet<>();
    v.path("rentalOptions").forEach(x -> options.add(x.asText()));
    return options.containsAll((List<?>) q.getOrDefault("rentalOptions", List.of()));
  }

  public Comparator<ListingRecord> order(String sort) {
    Comparator<ListingRecord> comparator =
        switch (sort) {
          case "DEPOSIT_ASC" ->
              Comparator.comparingLong(x -> x.fields().path("price").path("deposit").asLong());
          case "MONTHLY_RENT_ASC" ->
              Comparator.comparingLong(x -> x.fields().path("price").path("monthlyRent").asLong());
          case "AREA_DESC" ->
              Comparator.<ListingRecord>comparingDouble(
                      x -> x.fields().path("exclusiveAreaM2").asDouble())
                  .reversed();
          default ->
              Comparator.<ListingRecord, String>comparing(
                      x -> x.fields().path("createdAt").asText())
                  .reversed();
        };
    return comparator.thenComparing(x -> x.id().toString());
  }

  private double number(Object value) {
    return ((Number) value).doubleValue();
  }

  private void fail(int status, String code) {
    throw new ApiException(status, code);
  }
}
