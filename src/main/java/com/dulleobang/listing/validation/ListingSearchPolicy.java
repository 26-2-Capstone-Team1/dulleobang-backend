package com.dulleobang.listing.validation;

import com.dulleobang.common.exception.ApiException;
import java.util.List;
import java.util.Map;
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

  private double number(Object value) {
    return ((Number) value).doubleValue();
  }

  private void fail(int status, String code) {
    throw new ApiException(status, code);
  }
}
