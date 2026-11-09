package com.dulleobang.listing.mapper;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MobileListingInputMapper {
  public static final Map<String, String> OPTIONS =
      Map.of(
          "parking",
          "PARKING",
          "pet",
          "PET_ALLOWED",
          "elevator",
          "ELEVATOR",
          "aircon",
          "AIR_CONDITIONER",
          "fridge",
          "REFRIGERATOR",
          "washer",
          "WASHING_MACHINE",
          "bed",
          "BED",
          "desk",
          "DESK",
          "closet",
          "WARDROBE",
          "induction",
          "INDUCTION");

  public ObjectNode convert(JsonNode body) {
    if (body.path("latitude").isMissingNode()
        || body.path("latitude").isNull()
        || body.path("longitude").isMissingNode()
        || body.path("longitude").isNull())
      throw new ApiException(422, "LOCATION_COORDINATES_REQUIRED");
    var value = new LinkedHashMap<String, Object>();
    value.put(
        "location",
        Map.of(
            "roadAddress",
            body.path("location").asText(),
            "latitude",
            body.path("latitude").asDouble(),
            "longitude",
            body.path("longitude").asDouble()));
    var price = new LinkedHashMap<String, Object>();
    price.put("leaseType", body.path("dealType").asText());
    price.put("deposit", Math.multiplyExact(body.path("price").path("deposit").asLong(), 10000));
    price.put(
        "monthlyRent", Math.multiplyExact(body.path("price").path("monthly").asLong(), 10000));
    price.put(
        "maintenanceFee",
        body.path("maintenanceFee").isNumber()
            ? body.path("maintenanceFee").asLong() * 10000
            : null);
    price.put("currency", "KRW");
    value.put("price", price);
    value.put("exclusiveAreaM2", body.path("area"));
    value.put("roomType", body.path("roomType"));
    value.put("availableFrom", body.path("availableFrom"));
    var options = new ArrayList<String>();
    body.path("rentalOptions").forEach(v -> options.add(OPTIONS.get(v.asText())));
    value.put("rentalOptions", options);
    for (var pair :
        Map.of(
                "floor",
                "floor",
                "totalFloors",
                "totalFloors",
                "supplyArea",
                "supplyAreaM2",
                "rooms",
                "roomCount",
                "bathrooms",
                "bathroomCount")
            .entrySet())
      if (body.has(pair.getKey())) value.put(pair.getValue(), body.get(pair.getKey()));
    return (ObjectNode) StrictJson.parse(StrictJson.stringify(value));
  }
}
