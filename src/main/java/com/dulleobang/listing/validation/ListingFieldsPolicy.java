package com.dulleobang.listing.validation;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class ListingFieldsPolicy {
  public ObjectNode merge(JsonNode input, JsonNode previous) {
    var value = (ObjectNode) (previous == null ? input.deepCopy() : previous.deepCopy());
    input.fields().forEachRemaining(e -> value.set(e.getKey(), e.getValue()));
    if (!value.has("floor")) value.putNull("floor");
    var location = (ObjectNode) value.path("location");
    String road = location.path("roadAddress").asText().strip();
    if (road.isEmpty()) fail("INVALID_LOCATION");
    location.put("roadAddress", road);
    var price = value.path("price");
    boolean monthly = price.path("leaseType").asText().equals("MONTHLY");
    if (monthly ? price.path("monthlyRent").asLong() <= 0 : price.path("monthlyRent").asLong() != 0)
      fail("INVALID_PRICE");
    if (value.has("supplyAreaM2")
        && value.path("supplyAreaM2").asDouble() < value.path("exclusiveAreaM2").asDouble())
      fail("INVALID_AREA");
    if (!value.path("floor").isNull()
        && value.has("totalFloors")
        && value.path("floor").asInt() > value.path("totalFloors").asInt()) fail("INVALID_FLOOR");
    return value;
  }

  private void fail(String code) {
    throw new ApiException(422, code);
  }
}
