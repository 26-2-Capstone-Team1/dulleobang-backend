package com.dulleobang.listing.mapper;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MobileSearchMapper {
  public Map<String, List<String>> convert(Map<String, List<String>> raw) {
    Map<String, List<String>> values = new LinkedHashMap<>(raw);
    {
      for (var pair :
          Map.of("location", "q", "dealType", "leaseType", "maxPrice", "maxMonthlyRent").entrySet())
        if (values.containsKey(pair.getKey())) {
          if (values.containsKey(pair.getValue()))
            throw new com.dulleobang.common.exception.ApiException(
                400, "AMBIGUOUS_QUERY_PARAMETER");
          values.put(pair.getValue(), values.remove(pair.getKey()));
        }
      for (String key : List.of("maxDeposit", "maxMonthlyRent"))
        if (values.containsKey(key)) {
          try {
            values.put(
                key,
                values.get(key).stream()
                    .map(
                        v -> {
                          if (!v.matches("[0-9]+")) throw new IllegalArgumentException();
                          return Long.toString(Math.multiplyExact(Long.parseLong(v), 10000));
                        })
                    .toList());
          } catch (Exception ex) {
            throw new com.dulleobang.common.exception.ApiException(400, "INVALID_QUERY_PARAMETER");
          }
        }
    }
    return values;
  }
}
