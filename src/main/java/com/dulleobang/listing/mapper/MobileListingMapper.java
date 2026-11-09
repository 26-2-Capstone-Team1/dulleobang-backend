package com.dulleobang.listing.mapper;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MobileListingMapper {
  public ObjectNode convert(ObjectNode value) {
    var location = value.path("location");
    var price = value.path("price");
    var owner = value.path("owner");
    var result = (ObjectNode) StrictJson.parse("{}");
    result.set("listingId", value.get("listingId"));
    result.put("location", location.path("roadAddress").asText());
    price(result, price);
    result.set("dealType", price.get("leaseType"));
    result.set("latitude", location.get("latitude"));
    result.set("longitude", location.get("longitude"));
    result.set("area", value.get("exclusiveAreaM2"));
    media(result, value);
    result.set("isScrapped", value.get("isScrapped"));
    result.set("status", value.get("status"));
    var info = result.putObject("space3DInfo");
    info.put("status", value.path("currentSpaceVersion").isNull() ? "NONE" : "COMPLETED");
    info.set("jobId", value.get("activeJobId"));
    details(result, value);
    return result;
  }

  private void price(ObjectNode result, com.fasterxml.jackson.databind.JsonNode price) {
    var money = (ObjectNode) StrictJson.parse("{}");
    money.put(
        "deposit",
        price.path("deposit").decimalValue().divide(java.math.BigDecimal.valueOf(10000)));
    money.put(
        "monthly",
        price.path("monthlyRent").decimalValue().divide(java.math.BigDecimal.valueOf(10000)));
    result.set("price", money);
    if (price.path("maintenanceFee").isNull()) result.putNull("maintenanceFee");
    else
      result.put(
          "maintenanceFee",
          price.path("maintenanceFee").decimalValue().divide(java.math.BigDecimal.valueOf(10000)));
  }

  private void media(ObjectNode result, ObjectNode value) {
    var options = result.putArray("rentalOptions");
    value
        .path("rentalOptions")
        .forEach(
            v ->
                options.add(
                    MobileListingInputMapper.OPTIONS.entrySet().stream()
                        .filter(e -> e.getValue().equals(v.asText()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(v.asText().toLowerCase(Locale.ROOT))));
    var uris = result.putArray("imageUris");
    value.path("photos").forEach(v -> uris.add(v.path("url").asText()));
    if (value.path("photos").isEmpty()) result.putNull("thumbnailUri");
    else result.set("thumbnailUri", value.path("photos").get(0).get("url"));
    var owner = value.path("owner");
    var agent = result.putObject("agent");
    agent.set("userId", owner.get("userId"));
    agent.set("name", owner.get("displayName"));
    agent.set("officeName", owner.get("officeName"));
    agent.set("phone", owner.get("phone"));
    agent.set("isVerifiedBroker", owner.get("isVerifiedListingAgent"));
  }

  private void details(ObjectNode result, ObjectNode value) {
    for (var e :
        Map.of(
                "roomType",
                "roomType",
                "floor",
                "floor",
                "totalFloors",
                "totalFloors",
                "supplyAreaM2",
                "supplyArea",
                "roomCount",
                "rooms",
                "bathroomCount",
                "bathrooms",
                "availableFrom",
                "availableFrom",
                "livingEnvironment",
                "livingEnvironment")
            .entrySet()) if (value.has(e.getKey())) result.set(e.getValue(), value.get(e.getKey()));
  }
}
